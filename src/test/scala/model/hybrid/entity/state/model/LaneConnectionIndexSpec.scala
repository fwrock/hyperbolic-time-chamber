package org.interscity.htc
package model.hybrid.entity.state.model

import core.util.JsonUtil
import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.util.Random

class LaneConnectionIndexSpec extends AnyFlatSpec with Matchers {
  "LaneConnectionIndex" should "match the list-based lookup on randomized connections" in {
    val rnd = new Random(7)
    (1 to 200).foreach { _ =>
      val connections = List.fill(rnd.nextInt(12))(LaneConnection(rnd.nextInt(5).toLong, rnd.nextInt(4), rnd.nextInt(4)))
      val index = LaneConnectionIndex(connections)
      (0L to 6L).foreach { next =>
        val expected = connections.filter(_.outgoingLinkId == next).map(_.fromLane)
        index.legal(next) shouldBe expected.toVector
        index.legalSet(next) shouldBe expected.toSet
      }
    }
  }

  "LinkState.locateVehicle" should "find the lane and vehicle, and not leak the derived index into JSON" in {
    val state = LinkState(0, from = 1, to = 2, length = 100, lanes = 2, speedLimit = 50, capacity = 100,
      freeSpeed = 50, simulationMode = SimulationModeEnum.MICRO, microTicksPerGlobalTick = 1,
      laneConnections = List(LaneConnection(9L, 1, 1))).initializeMicroLanes()
    state.vehiclesByLane(1).enqueue(VehicleInLane(5L, "s", 10.0, 1.0, 0.0, 4.5))
    state.locateVehicle(5L).map(_._1) shouldBe Some(1)
    state.locateVehicle(6L) shouldBe None
    state.laneConnectionIndex.legal(9L) shouldBe Vector(1)
    JsonUtil.toJson(state) should not include "laneConnectionIndex"
  }
}
