Overview
---------------------
    Suppose you have some entities that you have linked to Freebase Entities. Given a Freebase mid for each of these entities, use this code to grab lots of facts about these entities using the Google Freebase API.
    Note that this executes many http requests in parallel and processes them asynchronously using Scala Futures. Performance will be improved on a multicore machine.

    We provide support for persisting the data to Redis, a key-value store. Querying the Google API is obviously slower than querying a local database, so you should only execute requests for things that you haven't asked for before.
    The values we keep in Redis are simply the raw JSON returned by the Google API.


Setup
---------------------
    1) Request a key for the Google API and put it in the base directory for this project in a file called GOOGLE_API.key.
    2) (optional) Install Redis and start up redis-server.
    3) Make a file with one Freebase mid per line
    4) Perhaps modify src//main/scala/edu/umass/cs/iesl/freebase/FreebaseQuery.scala to have additional relations to extract from Freebase. Instructions at the top of object FreebaseQuery
    5) Log in to the Google API Console and set your allowed requests per second to some large value. The QueryExecutor has a built in rate limiter. Set val timeBetweenQueries  to something appropriate for your rate quota.


Command Line Options
---------------------
    (to specify an option, give, for example --write-to-redis=true)

    **write-to-redis** Whether to write stuff to Redis (default false)

    **read-from-redis** Whether to read stuff from Redis  (default false)

    **redis-socket**  Redis Socket    (default 6379)

    **redis-host** Redis host address  (default localhost)

    **output-file**  Where to write out the relations as a flat file

    **mid-file**  a file with one freebase mid per line (mids look like "/m/abcde" )
