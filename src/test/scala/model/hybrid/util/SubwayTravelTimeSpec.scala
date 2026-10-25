package org.interscity.htc.model.hybrid.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SubwayTravelTimeSpec extends AnyFlatSpec with Matchers {
  "subway travel time" should "convert metres and metres per second directly to whole simulation seconds" in {
    SubwayUtil.timeToNextStation(1000.0, 22.22) shouldBe 46L
    SubwayUtil.timeToNextStation(1000.0, 20.0) shouldBe 50L
    SubwayUtil.timeToNextStation(0.0, 20.0) shouldBe 0L
  }
  it should "reject unusable rail distances and velocities" in {
    for ((distance, speed) <- Seq((-1.0, 20.0), (1000.0, 0.0), (1000.0, -1.0),
      (Double.NaN, 20.0), (1000.0, Double.PositiveInfinity)))
      intercept[IllegalArgumentException](SubwayUtil.timeToNextStation(distance, speed))
  }
}
