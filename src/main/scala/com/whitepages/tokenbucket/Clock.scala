package com.whitepages.tokenbucket

trait Clock {
  def now: Long
}

case class RealtimeClock() extends Clock {
  def now = System.nanoTime()
}

