package com.whitepages.tokenbucket

import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.blocking

trait Requestable {
  def requestTokens(num: Long): Long
}
trait Drainable {
  def drain(): Unit
}

trait BucketLike extends Requestable with Drainable {
  protected var bucket: Long = initialBucketSize()
  def initialBucketSize(): Long = 0L
  def refillBucket(): Unit = { }
  override def drain(): Unit = bucket = 0
  def availableTokens(): Long = bucket

  val start = System.currentTimeMillis()
  def printBucket(s: String) = {
    println(System.currentTimeMillis() - start + ": " + s + ": " + availableTokens)
  }
}

// A bucket that can refill itself from a given TokenSource
trait Refillable extends BucketLike {
  val source: TokenSource
  override def refillBucket(): Unit = {
    bucket = (bucket + source.getTokens) min Long.MaxValue
  }
  override def drain(): Unit = {
    refillBucket()
    super.drain()
  }
  abstract override def requestTokens(num: Long): Long = {
    refillBucket()
    super.requestTokens(num)
  }
}

// A bucket with some initial supply of tokens.
trait InitialCapacity extends BucketLike {
  val initialCapacity: Long
  override def initialBucketSize() = initialCapacity
}

// A bucket whose maximum capacity is capped
trait SizeLimited extends BucketLike {
  val sizeLimit: Long
  override def refillBucket() = {
    super.refillBucket()
    bucket = bucket min sizeLimit
  }
}

// If requested number of tokens isn't available, block until
// we've refilled enough to satisfy the request instead of returning
// the number available.
// Without this, a bucket is non-blocking.
trait Strict extends Refillable {
  abstract override def requestTokens(num: Long): Long = {
    var foundSoFar = super.requestTokens(num)
    while (foundSoFar < num) {
      val remaining = num - foundSoFar
      // hm. knowing about source types has a smell
      source match {
        case predictableSource: PredictableTokenSource =>
          // can't sleep for "remaining" unless we're aware of SizeLimited, so just wait for the next one
          val predictedSleep = predictableSource.predictNext(1).toMillis
          if (predictedSleep > 0) blocking { Thread.sleep(predictedSleep) }
        case _ =>
          blocking { Thread.sleep(remaining) } // Um? One ms per token? What else could be done here?
      }
      foundSoFar = foundSoFar + super.requestTokens(remaining)
    }
    foundSoFar
  }
}
// The order of these two is important, so here's a shortcut
trait StrictLimited extends Strict with SizeLimited

trait WithLock {
  private val lock = new ReentrantLock()
  def withLock[T](f: => T) = {
    lock.lock()
    try { f } finally { lock.unlock() }
  }
}

trait ThreadsafeRequest extends BucketLike with WithLock {
  abstract override def requestTokens(num: Long) = {
    withLock {
      super.requestTokens(num)
    }
  }
}
trait ThreadsafeDrain extends BucketLike with WithLock {
  override def drain() {
    withLock {
      super.drain()
    }
  }
}

// Presumably your right-most mixin
trait Threadsafe extends ThreadsafeRequest with ThreadsafeDrain


abstract class TokenBucketBase extends BucketLike {
  override def requestTokens(num: Long): Long = {
    val availableTokens = num min bucket
    bucket = bucket - availableTokens
    availableTokens
  }
}
