package org.interscity.htc
package model.mobility.support.car

import model.mobility.entity.state.CarJourneyState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MesoLinkTimingSpec extends AnyFlatSpec with Matchers {
  "Default MESO link timing" should "round every link up, adding about half a tick per link" in {
    val j = CarJourneyState()
    var tick = 0L
    for (_ <- 1 to 3) tick = MesoLinkTiming.exitTick(tick, 3.4, originAtEnd = false, carry = false, j)
    tick shouldBe 12L // 3 x ceil(3.4) = 12 against 10.2 continuous
  }

  "A delegated exit tick" should "equal the one the vehicle computes itself, for any rounding state it hands over" in {
    for {
      carry <- Seq(false, true)
      credit <- Seq(0.0, 0.25, 0.9)
      lastExit <- Seq(-1L, 40L, 99L)
      seconds <- Seq(0.2, 3.4, 17.0)
      secondsPerTick <- Seq(1.0, 0.5)
    } {
      val entry = model.mobility.entity.event.data.DelegatedEntry(
        core.runtime.DelegatedWake("c", 1L, 3L), secondsPerTick, carry, credit, lastExit)
      val own = CarJourneyState(secondsPerTick = secondsPerTick)
      own.mesoRoundingCredit = credit
      own.mesoLastExitTick = lastExit
      MesoLinkTiming.delegatedExitTick(100L, seconds, entry, originAtEnd = false) shouldBe
        MesoLinkTiming.exitTick(100L, seconds / secondsPerTick, originAtEnd = false, carry = carry, own)
    }
  }

  "Carried rounding" should "keep the cumulative exit time within one tick of the continuous time" in {
    val j = CarJourneyState()
    var tick = 0L
    val exits = (1 to 3).map { _ => tick = MesoLinkTiming.exitTick(tick, 3.4, originAtEnd = false, carry = true, j); tick }
    exits shouldBe Seq(4L, 7L, 11L) // continuous 3.4, 6.8, 10.2: each exit is the next whole tick, without accumulating the surplus
    exits.zip(Seq(3.4, 6.8, 10.2)).foreach { case (e, c) => (e - c) should (be >= 0.0 and be < 1.0 + 1e-9) }
  }

  it should "let a wait at a node absorb the credit" in {
    val j = CarJourneyState()
    val first = MesoLinkTiming.exitTick(0L, 3.4, originAtEnd = false, carry = true, j) // exit 4, credit 0.6
    first shouldBe 4L
    j.mesoRoundingCredit shouldBe 0.6 +- 1e-9
    // entered one tick after the exit (a one-tick node dwell): the surplus is used up by the wait
    MesoLinkTiming.exitTick(first + 1, 3.4, originAtEnd = false, carry = true, j) shouldBe 9L // ceil(5 + 3.4) = 9
    j.mesoRoundingCredit shouldBe 0.6 +- 1e-9
  }

  it should "keep at least one tick per link and reset on a MESO origin" in {
    val j = CarJourneyState()
    MesoLinkTiming.exitTick(0L, 0.2, originAtEnd = false, carry = true, j) shouldBe 1L
    MesoLinkTiming.exitTick(1L, 0.2, originAtEnd = false, carry = true, j) shouldBe 2L
    MesoLinkTiming.exitTick(5L, 1.0, originAtEnd = true, carry = true, j) shouldBe 6L
    j.mesoRoundingCredit shouldBe 0.0
    j.mesoLastExitTick shouldBe -1L
  }
}
