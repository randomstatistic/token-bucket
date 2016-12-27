package com.whitepages.tokenbucket

import scala.concurrent.duration._

trait TokenSource {
  /**
    * Generate as many tokens as we can get right now, and return them
    *
    * @return The number of tokens actually available
    */
  def getTokens(): Long
}

trait PredictableTokenSource extends TokenSource {
  /**
    * Returns the length of time expected until num tokens will be available.
    * No guarantees are made that this is accurate.
    *
    * @param num The number of tokens
    * @return How long until we might get that many from a getTokens call
    */
  def predictNext(num: Long): FiniteDuration
}

trait ClockBasedTokenSource extends PredictableTokenSource{
  val clock: Clock
  val dripEvery: FiniteDuration

  private val dripEveryNanos = dripEvery.toNanos

  private var lastFilled = clock.now
  private def nextFill = lastFilled + dripEveryNanos

  override def getTokens(): Long = {
    val now = clock.now

    if (now >= nextFill) {
      val generatedTokens = (now - lastFilled) / dripEveryNanos
      lastFilled = lastFilled + generatedTokens * dripEveryNanos
      generatedTokens
    }
    else {
      0L
    }
  }
  def predictNext(): FiniteDuration = predictNext(1)
  override def predictNext(num: Long): FiniteDuration = {
    val now = clock.now
    (lastFilled - now + dripEveryNanos * num).nanos
  }
}

case class TimeTokenSource(
                            override val dripEvery: FiniteDuration,
                            override val clock: Clock = RealtimeClock()) extends ClockBasedTokenSource
