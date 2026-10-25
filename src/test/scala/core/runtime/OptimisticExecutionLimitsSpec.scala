package org.interscity.htc
package core.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OptimisticExecutionLimitsSpec extends AnyFlatSpec with Matchers {
  "a speculation window" should "include its boundary, respect the run horizon and saturate without overflow" in {
    val limits = OptimisticExecutionLimits(maxAheadTicks = 50L)
    limits.ceiling(100L, 1000L) shouldBe 150L
    limits.ceiling(100L, 120L) shouldBe 120L
    limits.ceiling(Long.MaxValue - 10L, Long.MaxValue - 1L) shouldBe Long.MaxValue - 1L
    limits.ceiling(Long.MaxValue, 1000L) shouldBe 1000L
    OptimisticExecutionLimits(maxAheadTicks = 0L).ceiling(10L, 100L) shouldBe 10L
  }
  it should "reject invalid resource budgets before startup" in {
    intercept[IllegalArgumentException](OptimisticExecutionLimits(maxAheadTicks = -1L))
    intercept[IllegalArgumentException](OptimisticExecutionLimits(maxUncommittedEvents = 0))
    intercept[IllegalArgumentException](OptimisticExecutionLimits(maxEventsPerTurn = 0))
  }
}
