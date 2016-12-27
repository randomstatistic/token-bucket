package com.whitepages.tokenbucket

import java.util.concurrent.Executors

import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class TestLeakyBucket extends FunSpec with Matchers {

  implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  val times = scala.collection.mutable.HashMap[String, FiniteDuration]()
  def time[T](name: String)(f: => T) = {
    val t1 = System.nanoTime()
    try {
      f
    } finally {
      times += (name -> (System.nanoTime() - t1).nanos)
    }
  }
  def timedAssertion[T](f: => T): FiniteDuration = {
    val t1 = System.nanoTime()
    f
    (System.nanoTime() - t1).nanos
  }

  def bucketTest(bucketRate: FiniteDuration, bucketSize: Int, initialDelay: FiniteDuration)(block: (AsyncBucket) => Unit): Unit = {
    val bucket = BucketBuilder(bucketRate, bucketSize)
    // get us aligned with the drip cycle
    bucket.drain()
    bucket.requestTokens()
    if (initialDelay > 0.seconds) Thread.sleep(initialDelay.toMillis)
    block(bucket)
  }

  describe("100ms rate") {

    it("should consume a half-full bucket quickly") {
      bucketTest(100.millis, 10, 500.millis) { bucket => {
        time("half-full bucket") {
          bucket.requestTokens(5)
        }
        times("half-full bucket").toMillis should be < 10L
      }}
    }

    it("should consume a full bucket quickly") {
      bucketTest(100.millis, 10, 1100.millis) { bucket => {
        time("full bucket") {
          bucket.requestTokens(10)
        }
        times("full bucket").toMillis should be < 10L
      }}
    }

    it("should consume from an empty bucket at the expected rate") {
      bucketTest(100.millis, 10, 0.millis) { bucket => {
        var assertionDelay = 0.millis
        Range(1, 10).foreach(i => {
          val timerName = "empty bucket run " + i
          time(timerName) {
            bucket.requestTokens()
          }
          // assertions like this appear to take almost 15ms, so factor that in to subseqent expectations
          assertionDelay = timedAssertion { times(timerName).toMillis should be(100L - assertionDelay.toMillis +- 15) }
       })
      }}
    }

    it("asking a full bucket for more than it has should not be quick") {
      bucketTest(100.millis, 10, 1100.millis) { bucket => {
        time("full bucket overflow") {
          bucket.requestTokens(11)
        }
        times("full bucket overflow").toMillis should be(100L +- 15)
      }}
    }
  }

  describe("10ms rate") {
    it("should consume from an empty bucket at the expected rate") {
      bucketTest(10.millis, 100, 0.millis) { bucket => {
        time("throughput") {
          Range(0, 100).foreach(i => {
            bucket.requestTokens()
          })
        }
        times("throughput").toMillis should be (1000L +- 25)
      }}
    }
    it("gets the expected rate with concurrent consumers") {
      var futures = List[Future[Long]]()

      bucketTest(10.millis, 100, 0.millis) { bucket => {
        time("concurrent throughput") {
          Range(0, 100).foreach( _ => {
            futures = futures :+ bucket.tokenF(1)
          })
          Await.ready(Future.sequence(futures), 2.seconds)
        }
        times("concurrent throughput").toMillis should be (1000L +- 25)
      }}
    }
  }

  describe("rate limited actions") {

    it ("should limit synchronous rate") {
      bucketTest(10.millis, 1, 0.millis) { bucket => {
        val results = time("rate limited loop") {
          for (i <- Range(0, 10)) yield {
            bucket.rateLimited() {
              i // some thrilling computation
            }
          }
        }
        times("rate limited loop").toMillis should be (100L +- 5)
        results should contain theSameElementsInOrderAs Range(0,10)
      }}
    }

    it("should rate limit async blocks") {
      bucketTest(50.millis, 1, 0.millis) { bucket => {
        val f = bucket.rateLimitedAsync(1) {
          1
        }
        val result = time("future completion") {
          Await.result(f, 1.seconds)
        }
        times("future completion").toMillis should be (50L +- 5)
        result should be(1)
      }}
    }

    it("should rate limit future completion") {
      bucketTest(10.millis, 1, 0.millis) { bucket => {
        val result = time("future completion") {
          Await.result(bucket.rateLimitedFuture(5)(Future.successful(1)), 1.seconds)
        }
        times("future completion").toMillis should be (50L +- 5)
        result should be(1)
      }}

    }
  }


}


