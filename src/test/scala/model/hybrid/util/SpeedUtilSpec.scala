package org.interscity.htc
package model.hybrid.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpeedUtilSpec extends AnyFlatSpec with Matchers {

  "bprCongestionFactor" should "return exactly 1.0 (no congestion) at zero volume" in {
    SpeedUtil.bprCongestionFactor(volume = 0.0, capacity = 100.0) shouldBe 1.0
  }

  it should "return 1.15 (the BPR default alpha) at volume == capacity" in {
    SpeedUtil.bprCongestionFactor(volume = 100.0, capacity = 100.0) shouldBe (1.15 +- 1e-9)
  }

  it should "grow steeply (quartic) past capacity, not linearly" in {
    val atCapacity  = SpeedUtil.bprCongestionFactor(volume = 100.0, capacity = 100.0)
    val over25pct   = SpeedUtil.bprCongestionFactor(volume = 125.0, capacity = 100.0)
    val over50pct   = SpeedUtil.bprCongestionFactor(volume = 150.0, capacity = 100.0)

    // BPR's beta=4 exponent means a 50% overload costs far more than double a 25% overload's
    // congestion penalty above baseline (1.0) — this is the whole point of using BPR instead of
    // a linear v/c penalty: it punishes over-capacity links sharply for routing purposes.
    val penalty25 = over25pct - 1.0
    val penalty50 = over50pct - 1.0
    (penalty50 / penalty25) should be > 2.0
    atCapacity should be < over25pct
    over25pct should be < over50pct
  }

  it should "not divide by zero when capacity is zero, returning the no-congestion default" in {
    SpeedUtil.bprCongestionFactor(volume = 10.0, capacity = 0.0) shouldBe 1.0
  }

  "linkDensitySpeed" should "return freeSpeed at zero occupancy" in {
    SpeedUtil.linkDensitySpeed(length = 300.0, capacity = 20.0, numberOfCars = 0L, freeSpeed = 13.9) shouldBe 13.9
  }

  it should "return the capacity-saturated fallback speed (1.0 m/s) at or above capacity" in {
    SpeedUtil.linkDensitySpeed(length = 300.0, capacity = 20.0, numberOfCars = 20L, freeSpeed = 13.9) shouldBe 1.0
    SpeedUtil.linkDensitySpeed(length = 300.0, capacity = 20.0, numberOfCars = 25L, freeSpeed = 13.9) shouldBe 1.0
  }

  it should "decrease monotonically as occupancy grows while strictly below capacity" in {
    // Below capacity, speed follows the continuous density curve. At/above capacity it's a fixed
    // floor (1.0), not a continuation of that curve, so monotonicity is only guaranteed up to
    // (not across) the capacity boundary -- see the dedicated floor test below.
    val speeds = (0 until 20).map(n => SpeedUtil.linkDensitySpeed(length = 300.0, capacity = 20.0, numberOfCars = n.toLong, freeSpeed = 13.9))
    speeds.sliding(2).foreach { case Seq(a, b) => a should be >= b }
  }
}
