package org.interscity.htc
package model.hybrid.micro.strategy

import model.hybrid.entity.state.model.VehicleInLane
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class DefaultMicroSimulationStrategySpec extends AnyFlatSpec with Matchers {
  "MICRO lane ordering" should "choose the same leader for equal-position vehicles regardless of arrival order" in {
    val lane = mutable.Queue.empty[VehicleInLane]
    VehicleInLane.insertFrontToBack(lane, VehicleInLane(15L, "cars", 0.0, 0.0, 0.0, 4.5))
    VehicleInLane.insertFrontToBack(lane, VehicleInLane(13L, "cars", 0.0, 0.0, 0.0, 4.5))

    lane.map(_.actorId) shouldBe Seq(13L, 15L)

    val strategy = DefaultMicroSimulationStrategy()
    val updates = strategy.executeSubTick(
      vehiclesByLane = mutable.Map(0 -> lane),
      subTick = 0,
      tick = 35L,
      linkLength = 200.0,
      speedLimit = 50.0,
      microTimeStep = 1.0,
      microTicksPerGlobalTick = 1,
      vehicleWaitingSeconds = mutable.Map.empty
    ).map(update => update.vehicleId -> update.leaderVehicle).toMap

    updates(13L) shouldBe None
    updates(15L) shouldBe Some(13L)
    lane.map(_.actorId) shouldBe Seq(13L, 15L)
  }

  it should "keep equal positions in stable ID order after each sub-tick" in {
    val lane = mutable.Queue(
      VehicleInLane(15L, "cars", 0.0, 0.0, 0.0, 4.5),
      VehicleInLane(13L, "cars", 0.0, 0.0, 0.0, 4.5)
    )
    val strategy = DefaultMicroSimulationStrategy()

    strategy.executeSubTick(
      vehiclesByLane = mutable.Map(0 -> lane),
      subTick = 0,
      tick = 35L,
      linkLength = 200.0,
      speedLimit = 50.0,
      microTimeStep = 0.1,
      microTicksPerGlobalTick = 1,
      vehicleWaitingSeconds = mutable.Map.empty
    )

    lane.map(_.actorId) shouldBe Seq(13L, 15L)
  }
}
