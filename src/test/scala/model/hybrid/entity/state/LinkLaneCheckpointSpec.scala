package org.interscity.htc
package model.hybrid.entity.state

import core.util.JsonUtil
import org.interscity.htc.model.hybrid.entity.state.model.VehicleInLane
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class LinkLaneCheckpointSpec extends AnyFlatSpec with Matchers {
  "a populated Link checkpoint" should "preserve integer lane keys, typed vehicles and independent queues on restore" in {
    val vehicles = mutable.Queue(VehicleInLane(11L, "hybrid.actor.Car", 20.0, 5.0, 0.0, 4.5),
      VehicleInLane(12L, "hybrid.actor.Car", 0.0, 3.0, 0.0, 4.5))
    val original = LinkState(0L, from = 1L, to = 2L, length = 200.0, lanes = 2,
      speedLimit = 10.0, capacity = 100.0, freeSpeed = 10.0,
      vehiclesByLane = Map(0 -> vehicles, 1 -> mutable.Queue.empty[VehicleInLane]))
    val restored = JsonUtil.fromJson[LinkState](JsonUtil.toJson(original))
    restored.vehiclesByLane.keysIterator.map(_ + 1).toVector.sorted shouldBe Vector(1, 2)
    restored.vehiclesByLane(0).head.actorId shouldBe 11L
    restored.vehiclesByLane(0).map(_.position).sum shouldBe 20.0
    restored.vehiclesByLane(0).dequeue()
    original.vehiclesByLane(0).size shouldBe 2
    restored.vehiclesByLane(0).head.actorId shouldBe 12L
  }
}
