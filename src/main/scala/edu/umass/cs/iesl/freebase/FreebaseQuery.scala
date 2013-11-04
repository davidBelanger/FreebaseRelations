package edu.umass.cs.iesl.freebase

import concurrent.Future
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.http.{HttpResponseException, HttpHeaders, ByteArrayContent, GenericUrl}
import java.lang.Exception
import util.{Failure, Success}
import concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import play.api.libs.json._
import collection.mutable.ArrayBuffer
import concurrent.stm.Source


object FreebaseQuery {
  val httpTransport  = new NetHttpTransport();
  val requestFactory = httpTransport.createRequestFactory();
  val base = "https://www.googleapis.com/freebase/v1/mqlread"
  //val base = "http://dime.labs.freebase.com/api/service/mqlread"
  val apiKey = io.Source.fromFile("GOOGLE_API.key").getLines().next()
  val baseQueryString = "[{ \"name\": null, \"id\": null, \"mid\": null, \"optional\": true }]"


  val organizationKeys =   Seq()

  val twoDeepOrganizationKeys = Seq(
  ("/organization/organization/headquarters","/location/mailing_address/citytown","headquarters_city") ,
    ("/organization/organization/parent","/organization/organization_relationship/parent","parent_organization") ,
    ("b:/organization/organization/parent","/organization/organization_relationship/child","child_organization"),
   // ("/organization/organization/founders","/organization/organization_founder") ,
    ("/organization/organization/board_members","/organization/organization_board_membership/member","board_member")  ,
  ("/organization/organization/leadership","/organization/leadership/person","organization_leader")

  )

  val personKeys = Seq(
    ("/people/person/nationality" , "nationality"),
    ("/people/person/place_of_birth", "place_of_birth"),
    ("/people/person/religion", "religion"),
    ("/people/deceased_person/place_of_death", "place_of_death" ),
    ("/people/deceased_person/cause_of_death", "cause_of_death"),
    ("/people/person/children", "children" ),
    ("/people/person/parents","parents"))
  val twoDeepPersonKeys = Seq(
    ("/people/person/places_lived","/people/place_lived/location","place_lived") ,
    ("/people/person/sibling_s","/people/sibling_relationship/sibling","sibling"),
    ("/people/person/spouse_s","/people/marriage/spouse","spouse"),
    ("/people/person/education","/education/education/institution","educaton_institution"),
    ("/people/person/employment_history","/business/employment_tenure/company","employer")
  )

  def baseQuery(key: String): String = {
    "\"" + key + "\":" + baseQueryString
  }
  def makeQueryString(mid: String,oneDeepKeys: Seq[(String,String)],twoDeepKeys: Seq[(String,String,String)]): String = {

    val innerFields = oneDeepKeys.map(x => baseQuery(x._1))

    val deepInnerFields = twoDeepKeys.map(outer_inner => {
      "\"" + outer_inner._1 + "\": " + "[{\"optional\": true, " + baseQuery(outer_inner._2) + "}]"
    })


    val query = "[{ \"limit\":1, \"name\": null, \"type\": [], \"mid\": \"" + mid + "\", " + (innerFields ++ deepInnerFields).mkString(",") + "}]"

    query
  }

//  val entityTypes = Seq("/people/person","/location/location","/organization/organization")
  val entityTypes = Seq("/people/person","/organization/organization")

  def getTypeQuery(mid: String): String = {
    "[{ \"limit\":1, \"name\": null, \"type\": [], \"mid\": \"" + mid +"\"}]"
  }


  def main( args: Array[String]) {

    val useGet = false

    val allExtractedRelations = new ArrayBuffer[FreebaseRelation]()
    object aERMutex

    val futures =
      for(mid <- io.Source.fromFile("mids").getLines().take(500)) yield {
        Future[String] {
          try {

            val typ = getEntityType(mid)

              if(typ.isDefined ){
                val (query,oneDeepKeys,twoDeepKeys) = {
                  typ.get match {
                    case "/people/person" => (makeQueryString(mid,personKeys,twoDeepPersonKeys) ,personKeys,twoDeepPersonKeys)
                    //case "/location/location" => (makeQueryString(mid,locationKeys,twoDeepLocationKeys),locationKeys,twoDeepLocationKeys)
                    case "/organization/organization" => (makeQueryString(mid,organizationKeys,twoDeepOrganizationKeys) ,organizationKeys,twoDeepOrganizationKeys)
                  }
                }
                val response = executeQuery(query,false)
                val name = (response \ "name").toString()
                val mid2 = (response \ "mid").toString().replaceAll("\"","")
                assert(mid2 == mid,"mid2 = " + mid2 + " mid1 = " + mid)

                val thisEntity = FreebaseEntity(name,mid)
                val extractedRelations = ArrayBuffer[FreebaseRelation]()

                var strs =  new ArrayBuffer[String]()  +=  name + "\t"
                for (key <- oneDeepKeys){
                  val resp =  (response \ key._1 ).as[List[JsValue]]
                  val ents = resp.map(r => FreebaseEntity(r))
                  extractedRelations ++= ents.map(e => FreebaseRelation(thisEntity,e,key._2))
                  if(!resp.isEmpty) strs += "\t" + key + "\t" + ents.map(_.toString).mkString(",") + "\t"

                }
                for(key <- twoDeepKeys){
                  val resp =  (response \ key._1 \\ key._2)
                  if(!resp.isEmpty){
                    val ents = resp.map(r => FreebaseEntity(r))
                    extractedRelations ++= ents.map(e => FreebaseRelation(thisEntity,e,key._3))
                    strs += "\t" + key + "\t" + ents.map(_.toString).mkString(",") + "\t"

                  }

                }
                aERMutex.synchronized{
                  allExtractedRelations ++= extractedRelations
                }

                val tr = extractedRelations.mkString("\n")
                tr

              }else{
                "no entity type found"
              }
          } catch {
            case ex: HttpResponseException => ex.getContent
            case   ex: Exception  => ex.getStackTrace.mkString(",");

          }
        }
      }


    val fs = futures.foreach(f =>
      f onComplete {
        case Success(result) => {if(result != "no entity type found")  println(result ) }
        case Failure(e)      => println(e.getStackTrace.mkString(","))

      })
    val waitingList = Future.sequence(futures.toSeq)
    waitingList.onComplete {
      case Success(results) => println("Extacted:\n" + allExtractedRelations.mkString("\n") )
    }

    Await.result(waitingList,100 seconds)
  }

  def executeQuery(query:String,useGet: Boolean): JsValue = {
    val request = makeRequest(query,useGet)
    val httpResponse = request.execute()

    val response = (Json.parse(httpResponse.parseAsString()) \ "result").apply(0)   //todo: should we be doing this apply(0)?
    response
  }

  def getEntityType(mid: String): Option[String] = {
    val typeQuery = getTypeQuery(mid)
    val response = executeQuery(typeQuery,true)

    val typ = (response \ "type").as[Seq[String]]
    for(entityType  <- entityTypes){
      if (typ.contains(entityType)){
        val name = (response \ "name").toString
        //println(name + " has type " + entityType)
        return Some(entityType)
      }
    }
    return None

  }

  def makeRequest(query: String,useGetRequest: Boolean) = {
    val request = if (useGetRequest){
      val url = new GenericUrl(base);
      url.put("query", query);
      url.put("key", apiKey);
      requestFactory.buildGetRequest(url);

    }else{
      val data = "query=" + query
      val content = new ByteArrayContent("application/x-www-form-urlencoded", data.getBytes());
      val url = new GenericUrl(base);
      url.put("key", apiKey);

      val request = requestFactory.buildPostRequest(url, content);
      val headers = new HttpHeaders();
      headers.put("X-HTTP-Method-Override","GET");
      request.setHeaders(headers);
      request
    }
    request
  }
}

object FreebaseEntity{
  def apply(r: JsValue): FreebaseEntity = {
    if((r \\ "name").headOption.isEmpty ) println("error = " + r.toString())
    val name = (r \\ "name").head.toString().replaceAll("\"","")
    val mid =  (r \\ "mid").head.toString().replaceAll("\"","")
    FreebaseEntity(name,mid)
  }

}


case class FreebaseRelation(e1: FreebaseEntity, e2: FreebaseEntity,rel: String)

case class FreebaseEntity(name: String, mid: String)   {
  override def toString = name + "-" + mid
}
