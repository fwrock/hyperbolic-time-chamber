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
    // math.max(arrivalTick + 1, tick) = math.max(101, 100) => 101. The +1-tick minimum dwell branch
    // wins over the literal tick branch at this boundary — arriving exactly on time still forces
    // at least one tick of dwell before departure, it does not depart in the same tick it arrived.
    MinimumDwellLatenessPolicy.resolveDepartureTick(AtTick(100), arrivalTick = 100) shouldBe 101
  }

  "resolveDepartureTick with Duration" should "depart ticks after the real arrival tick, first arrival" in {
    MinimumDwellLatenessPolicy.resolveDepartureTick(Duration(30), arrivalTick = 200) shouldBe 230
  }

  it should "depart ticks after the real arrival tick, a different arrival, proving no shared state leaks in" in {
    MinimumDwellLatenessPolicy.resolveDepartureTick(Duration(30), arrivalTick = 1000) shouldBe 1030
  }

  "MinimumDwellLatenessPolicy" should "be a pure function of only (spec, arrivalTick) — no accumulated offset" in {
    // Design guarantee, not a runtime check: resolveDepartureTick's signature takes exactly
    // (spec: EndTimeSpec, arrivalTick: Tick) and nothing else — there is no "scheduleDelayOffsetTicks"
    // or other mutable/accumulated field it reads. Calling it twice with the same arguments, in any
    // order relative to other calls, must always yield the same result.
    val first = MinimumDwellLatenessPolicy.resolveDepartureTick(AtTick(500), arrivalTick = 480)
    val second = MinimumDwellLatenessPolicy.resolveDepartureTick(AtTick(500), arrivalTick = 480)

    first shouldBe second
    first shouldBe 500
  }
}
