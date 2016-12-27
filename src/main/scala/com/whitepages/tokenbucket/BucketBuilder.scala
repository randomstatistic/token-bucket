package com.whitepages.tokenbucket

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.blocking


// Some common implementations
class LeakyBucketImpl(override val source: TokenSource, override val sizeLimit: Long)
  extends TokenBucketBase
    with StrictLimited
    with Threadsafe

class TokenBucketImpl(override val source: TokenSource)
  extends TokenBucketBase
    with Strict
    with Threadsafe

// Wrappers to add convenience methods onto, and to further isolate Bucket internals
class Bucket(private val bucket: BucketLike) extends Requestable with Drainable {
  def requestToken(): Long = requestTokens(1)
  def requestTokens(): Long = requestTokens(1)

  override def requestTokens(num: Long): Long = bucket.requestTokens(num)
  override def drain(): Unit = bucket.drain()
}

class BlockingBucket(private val bucket: BucketLike with Strict) extends Bucket(bucket) {
  def rateLimited[T](num: Int = 1)(f: => T): T = {
    requestTokens(num)
    f
  }
}

class AsyncBucket(private val bucket: BucketLike with Strict with ThreadsafeRequest) extends BlockingBucket(bucket) {
  def tokenF(num: Int)(implicit ec: ExecutionContext): Future[Long] =
    Future{ blocking { bucket.requestTokens(num) } }(ec)

  def rateLimitedAsync[T](num: Int)(f: => T)(implicit ec: ExecutionContext): Future[T] = {
    tokenF(num).map(_ => f )
  }

  // Note, doesn't block the parameter future from executing, only anything chained off of that
  def rateLimitedFuture[T](num: Int)(f: Future[T])(implicit ec: ExecutionContext): Future[T] = {
    tokenF(num).flatMap(_ => f )
  }
}

// Shortcut builder methods
object BucketBuilder{
  def apply(dripEvery: FiniteDuration, maxSize: Long) = new AsyncBucket(new LeakyBucketImpl(TimeTokenSource(dripEvery), maxSize))
  def apply(dripEvery: FiniteDuration) = new AsyncBucket(new TokenBucketImpl(TimeTokenSource(dripEvery)))
  // returns a wrapped bucket, but create the best relevant WrappedBucket subtype in case of later type checking.
  def apply(bucket: BucketLike): Bucket = bucket match {
    case b: Strict with ThreadsafeRequest => new AsyncBucket(b)
    case b: Strict => new BlockingBucket(b)
    case _ => new Bucket(bucket)
  }
}
