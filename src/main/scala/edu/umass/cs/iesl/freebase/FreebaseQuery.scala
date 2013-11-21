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
import collection.immutable.HashMap
import collection.parallel.mutable

class FreeBasePath(path: Seq[String], name: String) {
  val baseQueryString = "[{ \"name\": null, \"id\": null, \"mid\": null, \"optional\": true }]"
  val jsonStr = toJsonStr()

  def fromJsValue(response: JsValue, entity: FreebaseEntity) : Seq[FreebaseRelation] = {
    val extractedRelations = ArrayBuffer[FreebaseRelation]()

    var jsv = response
    val len = path.length
    val resp =
      if(path.length == 1){
        jsv \\ path.last
      }else{
        for(key_index <- 0 until len - 1){
          jsv = jsv \ path(key_index)
        }
        jsv \\ path.last
      }

    if(!resp.isEmpty){
      val ents = resp.flatMap(r => FreebaseEntity(r))
      extractedRelations ++= ents.map(e => FreebaseRelation(entity,e,name))
    }
    extractedRelations
  }
  def toJsonStr(): String = {
    toJSonStrRecurse(0)
  }

  private def toJSonStrRecurse(curr_idx: Int): String =  {
    val curr_elem = path(curr_idx)
    if(path.length == curr_idx + 1) {
      "\"" + curr_elem + "\":" + baseQueryString
    } else {
      "\"" + curr_elem + "\": " + "[{\"optional\": true, " + toJSonStrRecurse(curr_idx + 1) + "}]"
    }
  }

}

object FreebaseQuery {

  //These are the only entity types we extract info for. If an entity doesn't have this type, we don't follow up with it.
  val entityTypes = Seq("/people/person","/organization/organization")



  //////////////////////////////////////////////////////
  // Here, we have hardcoded in various relations we seek to extract. In the tuples, the rightmost field is just the name we use for the relation.
  // The distinction between 'keys' and 'twoDeepKeys' are that the former are for single-hop paths in freebase and the others are for two hops.
  // We support arbitrarily long paths. Just instantiate a FreebasePath above (the paths below get converted into Freebase Paths later)
  val organizationKeys =   Seq()

  val twoDeepOrganizationKeys = Seq(
    ("/organization/organization/headquarters","/location/mailing_address/citytown","headquarters_city") ,
    ("/organization/organization/parent","/organization/organization_relationship/parent","parent_organization") ,
    ("b:/organization/organization/parent","/organization/organization_relationship/child","child_organization"),
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
  //////////////////////////////////////////////////////

  val baseQueryString = "[{ \"name\": null, \"id\": null, \"mid\": null, \"optional\": true }]"

  def baseQuery(key: String): String = {
    "\"" + key + "\":" + baseQueryString
  }


  def makeQuery(mid: String, fps: Seq[FreeBasePath]): String = {
    "[{ \"limit\":1, \"name\": null, \"type\": [], \"mid\": \"" + mid + "\"," + fps.map(_.jsonStr).mkString(",") + "}]"
  }

  def makeQueryString(mid: String,oneDeepKeys: Seq[(String,String)],twoDeepKeys: Seq[(String,String,String)]): String = {

    val innerFields = oneDeepKeys.map(x => baseQuery(x._1))

    val deepInnerFields = twoDeepKeys.map(outer_inner => {
      "\"" + outer_inner._1 + "\": " + "[{\"optional\": true, " + baseQuery(outer_inner._2) + "}]"
    })

    val query = "[{ \"limit\":1, \"name\": null, \"type\": [], \"mid\": \"" + mid + "\", " + (innerFields ++ deepInnerFields).mkString(",") + "}]"

    query
  }


  def getTypeQuery(mid: String): String = {
    "[{ \"limit\":1, \"name\": null, \"type\": [], \"mid\": \"" + mid +"\"}]"
  }

  var numRequestsOut = 0

  class FreebaseQueryOptions extends cc.factorie.util.DefaultCmdOptions {
    val writeToRedis = new CmdOption("write-to-redis", "false", "BOOL", "Whether to write stuff to Redis")
    val readFromRedis = new CmdOption("read-from-redis", "false", "BOOL", "Whether to read stuff from Redis")
    val redisSocket = new CmdOption("redis-socket", "6379","INT","Redis Socket")
    val redisHost = new CmdOption("redis-host", "localhost","STRING","Redis Host")
    val outputFile = new CmdOption("output-file","outputRelations.txt", "FILE","where to write out the relations as a flat file")
    val midFile =  new CmdOption("mid-file","mids", "FILE","where to read Freebase mids from")
  }

  def main( args: Array[String]) {

    val opts = new FreebaseQueryOptions
    opts.parse(args)

    import scala.concurrent._

    val QueryExecutor = new QueryExecutor(opts.redisHost.value,opts.redisSocket.value.toInt,opts.readFromRedis.value.toBoolean,opts.writeToRedis.value.toBoolean)

    object aERMutex
    val outputStream = new PrintWriter(opts.outputFile.value)

    val freebasePaths = collection.mutable.HashMap[String,Seq[FreeBasePath]]()
    freebasePaths += "/people/person" ->  (personKeys.map( k => new FreeBasePath(Seq(k._1),k._2)) ++ twoDeepPersonKeys.map(k => new FreeBasePath(Seq(k._1,k._2),k._3))).toSeq
    freebasePaths += "/organization/organization" -> (twoDeepOrganizationKeys.map(k => new FreeBasePath(Seq(k._1,k._2),k._3))).toSeq


    val futures =
      for(mid <- io.Source.fromFile(opts.midFile.value).getLines()) yield {
        future {
          try {

            val typ = getEntityType(mid,QueryExecutor)

            if(typ.isDefined ){
              val paths = freebasePaths(typ.get)
              val query =  makeQuery(mid,paths)

              //val response = blocking { QueryExecutor.executeQuery(mid + "-data",query,false) }
              val response =  QueryExecutor.executeQuery(mid + "-data",query,false)

              val string =
                aERMutex.synchronized{
                  val name = (response \ "name").toString().replaceAll("\"","")
                  val mid = (response \ "mid").toString().replaceAll("\"","")

                  val thisEntity = FreebaseEntity(name,mid)
                  val extractedRelations = paths.flatMap(_.fromJsValue(response,thisEntity))

                  val st = extractedRelations.map(_.tabDeliminatedString).mkString("\n")
                  outputStream.println(st)
                  outputStream.flush()
                  st
                }
              string
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

    Await.result(waitingList,1000000 seconds)
  }



  def getEntityType(mid: String, executor: QueryExecutor): Option[String] = {
    val typeQuery = getTypeQuery(mid)
    val response = executor.executeQuery(mid + "-type",typeQuery,true)

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

class QueryExecutor(jedisHost: String,jedisPort: Int,readFromJedis: Boolean, writeToJedis: Boolean){

  val timeBetweenQueries = 100 //.1 seconds
  object jedisMutex
  object jedisReadMutex

  val httpTransport  = new NetHttpTransport();
  val requestFactory = httpTransport.createRequestFactory();
  val base = "https://www.googleapis.com/freebase/v1/mqlread"
  //val base = "http://dime.labs.freebase.com/api/service/mqlread"
  val apiKey = io.Source.fromFile("GOOGLE_API.key").getLines().next()
  var mostRecentCall = System.currentTimeMillis
  val jedisConnection = (new Jedis(jedisHost,jedisPort,600000)).pipelined()
  val jedisReadConnection = new Jedis(jedisHost,jedisPort,600000)
  var writeCount = 0


  object timingMutex

  def getQueryResponseString(mid: String,query:String,useGet: Boolean): String = {
    if(readFromJedis){
      jedisReadMutex.synchronized{
        if(jedisReadConnection.exists(mid))
          return jedisReadConnection.get(mid)
      }
    }

    val request = makeRequest(query,useGet)
    val timeToWait =
    timingMutex.synchronized{
      val currentTime =  System.currentTimeMillis
      val ttw = math.max(0,mostRecentCall - currentTime + timeBetweenQueries)
      mostRecentCall = currentTime
      ttw
    }

    if(timeToWait > 0) println("waiting " + timeToWait)
    Thread.sleep(timeToWait)
    val httpResponse = request.execute()

    val responseStr = httpResponse.parseAsString()
    if(writeToJedis) {
      jedisMutex.synchronized{
        jedisConnection.set(mid,responseStr)
        writeCount += 1
        if(writeCount % 100 == 0) jedisConnection.sync()
      }
    }
    responseStr


  }

  def executeQuery(mid: String,query:String,useGet: Boolean): JsValue = {
    val responseString = getQueryResponseString(mid,query,useGet)
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


