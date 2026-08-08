package org.interscity.htc
package core.actor.manager.time.gvt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TerminationPlateauDetectorSpec extends AnyFlatSpec with Matchers {

  "observe" should "reject a plateauRoundsRequired below 1 at construction" in {
    an[IllegalArgumentException] should be thrownBy new TerminationPlateauDetector(plateauRoundsRequired = 0)
  }

  it should "never declare termination while any round reports not-idle" in {
    val detector = new TerminationPlateauDetector(plateauRoundsRequired = 3)

    detector.observe(allIdle = true, gvt = 100L) shouldBe false
    detector.observe(allIdle = true, gvt = 100L) shouldBe false // 2 plateau rounds so far
    detector.observe(allIdle = false, gvt = 100L) shouldBe false // reset by a non-idle round
    detector.observe(allIdle = true, gvt = 100L) shouldBe false // plateau restarts at round 1
    detector.observe(allIdle = true, gvt = 100L) shouldBe false // round 2
  }

  it should "declare termination once GVT plateaus for the required number of idle rounds" in {
    val detector = new TerminationPlateauDetector(plateauRoundsRequired = 3)

    detector.observe(allIdle = true, gvt = 50L) shouldBe false // round 1
    detector.observe(allIdle = true, gvt = 50L) shouldBe false // round 2
    detector.observe(allIdle = true, gvt = 50L) shouldBe true // round 3 — declare
  }

  it should "not treat a still-advancing GVT as a plateau even while idle every round" in {
    val detector = new TerminationPlateauDetector(plateauRoundsRequired = 2)

    // Idle but GVT keeps moving (e.g. the margin-based estimate catching up as LVTs converge) --
    // never a plateau, so never terminate.
    detector.observe(allIdle = true, gvt = 10L) shouldBe false
    detector.observe(allIdle = true, gvt = 20L) shouldBe false
    detector.observe(allIdle = true, gvt = 30L) shouldBe false
  }

  it should "declare immediately when plateauRoundsRequired is 1" in {
    val detector = new TerminationPlateauDetector(plateauRoundsRequired = 1)

    detector.observe(allIdle = true, gvt = 5L) shouldBe true
  }

  it should "restart the plateau count from the new GVT after reset" in {
    val detector = new TerminationPlateauDetector(plateauRoundsRequired = 2)

    detector.observe(allIdle = true, gvt = 10L) shouldBe false
    detector.observe(allIdle = true, gvt = 10L) shouldBe true

    detector.reset()

    detector.observe(allIdle = true, gvt = 10L) shouldBe false // plateau count restarted, not resumed
    detector.observe(allIdle = true, gvt = 10L) shouldBe true
  }
}
