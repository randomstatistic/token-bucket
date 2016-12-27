Token bucket
------------

Token buckets are a way to limit rate. You add (drip) tokens into a bucket of some size at some rate. If the bucket is full, no more tokens are added. Code can request a certain number of tokens as a prerequisite to permitting certain behavior. 

The bigger the bucket, the more "burstiness" you can handle without inhibiting rate. The faster you add tokens, the higher the permissable rate.

Generally, token buckets come in two flavors:
* Strict: A request for some number of tokens blocks until that many tokens can be consumed.
* Non-Strict: (Relaxed) A request for some number of tokens consumes the minimum of the number of tokens available or the number requested, and does not block.

Note: There is some confusion in the literature around the name "token bucket", and the related term "leaky bucket", so take the terminology with a grain of salt.
 
Simple usage
------------

The simplest usage is a bucket that adds tokens at a fixed rate, up to a given size:

    val bucketRate: FiniteDuration = 100.millis
    val bucketSize: Long = 10L
    val bucket = BucketBuilder(bucketRate, bucketSize)

A bucket created in this way has the following properties:

* A token will be added to the bucket every 100ms
* The bucket will keep up to 10 tokens
* Requests for tokens are threadsafe
* Is "Strict".

So, assuming the above bucket has had a chance to fill completely:

    bucket.requestTokens(10) // doesn't block
    // but now the bucket is empty, so
    bucket.requestTokens(10) // blocks for 100ms * 10 = 1 sec

There is also a threadsafe asynchronous interface to these simple buckets:

    val tokenFuture: Future[Long] = bucket.tokenF(10)
    // since this is a Strict bucket, the future value will always be the number of tokens requested.
    tokenFuture.map(_ => sendHttpRequest()) 
    
    //or if you prefer
    bucket.rateLimitedAsync(10) {
      sendHttpRequest()
    }


Design
------

The `BucketBuilder` interface is just assembling a bucket with certain common characteristics, and adding some convienence methods.
Custom buckets can be crafted to taste by mixing certain traits.

Some examples of bucket classes you could create:

    // Non-threadsafe, Non-Strict bucket with a certain number of starting tokens. More tokens are never added, making this not very useful.
    class OneShotBucket(override val initialCapacity: Int) extends TokenBucketBase with InitialCapacity

    // Threadsafe version of the same.
    class ThreadsafeOneShotBucket(override val initialCapacity: Int) extends TokenBucketBase with InitialCapacity with Threadsafe 
    
    // Non-Threadsafe, Non-Strict bucket with no initial tokens and no maximum capacity(!) that adds a token every 100ms
    class NonStrictBucket extends TokenBucketBase with Refillable {
      override val source: TokenSource = TimeTokenSource(100.millis)
    }
    
The `BucketBuilder` can also be used to wrap convenience functions around buckets that match certain required behavior:

    val asyncBucket = BucketBuilder(<any Strict and Threadsafe bucket>)
    asyncBucket.rateLimitedAsync(1) {
      ...
    }
 
 

Concurrency
-----------

Although buckets can be thread-safe, the default thread safety implementation is just a lock, so watch your threads. 

The async interfaces use an ExecutionContext to find a thread to block on, and the blocking call is [marked appropriately](http://www.scala-lang.org/api/2.11.6/index.html#scala.concurrent.package@blocking[T](body:=>T):T). 

References
----------

I wanted a leaky bucket implmementation that made rate-limited asynchronous operations easy in Scala. There were a few other libraries that came close that are worth mentioning.
 
* https://github.com/auidah/token-bucket - Scala. I pulled a lot of design ideas from here
* https://github.com/bbeck/token-bucket - Java. Not as many behavior variations, but looked pretty solid, and a little more well-known.
* https://github.com/vladimir-bukhtoyarov/bucket4j - Java. Very interesting approach to concurrency support.
