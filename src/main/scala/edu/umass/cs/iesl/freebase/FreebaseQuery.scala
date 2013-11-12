package edu.umass.cs.iesl.freebase

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.http.{HttpResponseException, HttpHeaders, ByteArrayContent, GenericUrl}
import java.lang.Exception
import concurrent.{ExecutionContext}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import play.api.libs.json._
import collection.mutable.ArrayBuffer
import java.io.{PrintWriter}
import redis.clients.jedis.Jedis

object ArbitraryLengthFreebaseQuery{
  val baseQueryString = "[{ \"name\": null, \"id\": null, \"mid\": null, \"optional\": true }]"
  def

}

object FreebaseQuery {

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
    ("/people/person/education","/education/education/institution","education_institution"),
    ("/people/person/employment_history","/business/employment_tenure/company","employer")
  )

  // TODO(aschein): Class for arbitrary length freebase path.


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

  var numRequestsOut = 0
  def main( args: Array[String]) {

    import scala.concurrent._
    val useGet = false

    val allExtractedRelations = new ArrayBuffer[FreebaseRelation]()
    object aERMutex
    val outputStream = new PrintWriter("outputRelations.txt")

    val futures =
      for(mid <- io.Source.fromFile("mids").getLines()) yield {
        future {
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
                val response = blocking { QueryExecutor.executeQuery(mid + "-data",query,false) }
                val name = (response \ "name").toString().replaceAll("\"","")
                val mid2 = (response \ "mid").toString().replaceAll("\"","")
                assert(mid2 == mid,"mid2 = " + mid2 + " mid1 = " + mid)

                val thisEntity = FreebaseEntity(name,mid)
                val extractedRelations = ArrayBuffer[FreebaseRelation]()


                var strs =  new ArrayBuffer[String]()  +=  name + "\t"
                for (key <- oneDeepKeys){
                  val resp =  (response \ key._1 ).as[List[JsValue]]
                  val ents = resp.flatMap(r => FreebaseEntity(r))
                  extractedRelations ++= ents.map(e => FreebaseRelation(thisEntity,e,key._2))
                  if(!resp.isEmpty) strs += "\t" + key + "\t" + ents.map(_.toString).mkString(",") + "\t"

                }
                for(key <- twoDeepKeys){
                  val resp =  (response \ key._1 \\ key._2)
                  if(!resp.isEmpty){
                    val ents = resp.flatMap(r => FreebaseEntity(r))
                    extractedRelations ++= ents.map(e => FreebaseRelation(thisEntity,e,key._3))
                    strs += "\t" + key + "\t" + ents.map(_.toString).mkString(",") + "\t"

                  }

                }
                aERMutex.synchronized{

                  //allExtractedRelations ++= extractedRelations

                  outputStream.println(extractedRelations.map(_.tabDeliminatedString).mkString("\n"))
                  outputStream.flush()
                }

                val tr = extractedRelations.map(_.tabDeliminatedString).mkString("\n")
                tr

              }else{
                "no entity type found"
              }
          } catch {
            case ex: HttpResponseException => ex.getContent
            case   ex: Exception  =>  ex.getMessage +   ex.getStackTrace.mkString(",")

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

    Await.result(waitingList,10000 seconds)
  }



  def getEntityType(mid: String): Option[String] = {
    val typeQuery = getTypeQuery(mid)
    val response = QueryExecutor.executeQuery(mid + "-type",typeQuery,true)

    val typ = (response \ "type").as[Seq[String]]
    for(entityType  <- entityTypes){
      if (typ.contains(entityType)){
        return Some(entityType)
      }
    }
    return None
  }


}

object FreebaseEntity{
  def apply(r: JsValue): Option[FreebaseEntity] = {
    if(! (r \\ "name").headOption.isEmpty ){
      val name = (r \\ "name").head.toString().replaceAll("\"","")
      val mid =  (r \\ "mid").head.toString().replaceAll("\"","")
      Some(FreebaseEntity(name,mid) )
    } else{
      None
    }

  }

}

object QueryExecutor{

  val timeBetweenQueries = 1 //.1 seconds
  object mutex
  val httpTransport  = new NetHttpTransport();
  val requestFactory = httpTransport.createRequestFactory();
  val base = "https://www.googleapis.com/freebase/v1/mqlread"
  //val base = "http://dime.labs.freebase.com/api/service/mqlread"
  val apiKey = io.Source.fromFile("GOOGLE_API.key").getLines().next()
  var mostRecentCall = System.currentTimeMillis
  def getJedis(): Jedis = new Jedis("localhost",6379,600000)


  def executeQuery(mid: String,query:String,useGet: Boolean): JsValue = {
    val jedis = getJedis()

    val responseString =
    if( jedis.exists(mid)){
      jedis.get(mid)
    }else{
      val request = makeRequest(query,useGet)
      mutex.synchronized{

        val currentTime =  System.currentTimeMillis
        val timeToWait = math.max(0,mostRecentCall - currentTime + timeBetweenQueries)
        mostRecentCall = currentTime

       // println("waiting " + timeToWait.toLong)
        Thread.sleep(timeToWait.toLong)
        val httpResponse = request.execute()

        val responseStr = httpResponse.parseAsString()
        jedis.set(mid,responseStr)
        responseStr
      }
    }

    val response = (Json.parse(responseString) \ "result").apply(0)  //note: this apply(0) makes sense, since we specified "limit":1 for the top-level query. this is fine, since we're querying by mid
    response
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

case class FreebaseRelation(e1: FreebaseEntity, e2: FreebaseEntity,rel: String)  {
  def tabDeliminatedString: String = Seq(e1.mid,e1.name,e2.mid,e2.name,rel).mkString("\t")
}

case class FreebaseEntity(name: String, mid: String)   {
  override def toString = name + "-" + mid
}


//object RelationAlignment{
//  def main( args: Array[String]) {
//    val relations = collection.mutable.HashMap[String,HashMap[String,FreebaseRelation]]()
//    val entitiesSeenInText = ArrayBuffer[String]()
//    for(line <- io.Source.fromFile("outputRelations.txt").getLines()){
//      val fields = line.split("\t")
//        val e1Id = fields(0)
//        val e2Id = fields(2)
//        val e1 =  FreebaseEntity(fields(1),fields(0),fields(3),fields(2),fields(4))
//        val relation = FreebaseRelation(e1,e2)
//        relations.getOrElseUpdate(e1Id,HashMap[String,FreebaseRelation]) += e2Id -> relation
//        relations.getOrElseUpdate(e2Id,HashMap[String,FreebaseRelation]) += e1Id -> relation
//        entitiesSeenInText += e1Id
//    }
//    println("things that were both in the corpus")
//  }
//
//}


