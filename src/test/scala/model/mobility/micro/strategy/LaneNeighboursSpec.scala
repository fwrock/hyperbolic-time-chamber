package org.interscity.htc
package model.mobility.micro.strategy

import model.mobility.entity.state.model.VehicleInLane
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.util.Random

class LaneNeighboursSpec extends AnyFlatSpec with Matchers {
  private def car(id: Long, pos: Double) = VehicleInLane(id, "s", pos, 1.0, 0.0, 4.5)

  "LaneNeighbours.sorted" should "equal the order-independent scan on randomized lanes with tied positions" in {
    val rnd = new Random(11)
    (1 to 2000).foreach { _ =>
      val n = rnd.nextInt(9)
      val ids = rnd.shuffle((1L to 20L).toList).take(n)
      val lane = mutable.Queue.from(ids.map(id => car(id, rnd.nextInt(5) * 10.0)).sortWith(VehicleInLane.precedes))
      LaneNeighbours.isSorted(lane) shouldBe true
      val probe =
        if (lane.nonEmpty && rnd.nextBoolean()) lane(rnd.nextInt(lane.length))
        else car(100L + rnd.nextInt(3), rnd.nextInt(6) * 10.0 - 5.0 * rnd.nextInt(2))
      LaneNeighbours.sorted(lane, probe) shouldBe LaneNeighbours.linear(lane, probe)
    }
  }

  "LaneNeighbours.isSorted" should "reject an unordered lane" in {
    LaneNeighbours.isSorted(mutable.Queue(car(1, 5.0), car(2, 9.0))) shouldBe false
  }
}
