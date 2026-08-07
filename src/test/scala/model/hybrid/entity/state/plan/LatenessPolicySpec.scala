package org.interscity.htc
package model.hybrid.entity.state.plan

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LatenessPolicySpec extends AnyFlatSpec with Matchers {

  "resolveDepartureTick with AtTick" should "depart exactly at the scheduled tick when arrival is early" in {
    MinimumDwellLatenessPolicy.resolveDepartureTick(AtTick(100), arrivalTick = 50) shouldBe 100
  }

  it should "depart one tick after arrival, never instantly, when arrival is already late" in {
    MinimumDwellLatenessPolicy.resolveDepartureTick(AtTick(100), arrivalTick = 150) shouldBe 151
  }

  it should "treat arrival exactly on the scheduled tick as late: arrivalTick + 1 wins, not the literal tick" in {

    MinimumDwellLatenessPolicy.resolveDepartureTick(AtTick(100), arrivalTick = 100) shouldBe 101
  }

  "resolveDepartureTick with Duration" should "depart ticks after the real arrival tick, first arrival" in {
    MinimumDwellLatenessPolicy.resolveDepartureTick(Duration(30), arrivalTick = 200) shouldBe 230
  }

  it should "depart ticks after the real arrival tick, a different arrival, proving no shared state leaks in" in {
    MinimumDwellLatenessPolicy.resolveDepartureTick(Duration(30), arrivalTick = 1000) shouldBe 1030
  }

  "MinimumDwellLatenessPolicy" should "be a pure function of only (spec, arrivalTick) — no accumulated offset" in {
 
    val first = MinimumDwellLatenessPolicy.resolveDepartureTick(AtTick(500), arrivalTick = 480)
    val second = MinimumDwellLatenessPolicy.resolveDepartureTick(AtTick(500), arrivalTick = 480)

    first shouldBe second
    first shouldBe 500
  }
}
