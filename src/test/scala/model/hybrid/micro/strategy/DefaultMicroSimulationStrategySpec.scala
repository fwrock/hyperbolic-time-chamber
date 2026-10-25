package org.interscity.htc
package model.hybrid.micro.strategy

import model.hybrid.entity.state.model.VehicleInLane
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class DefaultMicroSimulationStrategySpec extends AnyFlatSpec with Matchers {
  private def advance(lane: mutable.Queue[VehicleInLane], tick: Long = 1L, length: Double = 100.0,
                      signal: Option[model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum] = None) =
    DefaultMicroSimulationStrategy().executeSubTick(mutable.Map(0 -> lane), 0, tick,
      length, 50.0, 0.1, 10, mutable.Map.empty, signal)

  "MICRO boundary kinematics" should "keep a free vehicle's crossing speed and retire it at its first crossing sub-tick" in {
    val lane = mutable.Queue(VehicleInLane(1L, "cars", 99.5, 10.0, 0.0, 4.5))
    val update = advance(lane).head
    update.reachedEnd shouldBe true
    update.subTick shouldBe 0
    update.velocity shouldBe 10.26 +- 1e-9
    update.position shouldBe 100.0
    update.remainingStepSeconds shouldBe (1.026 - 0.5) / 10.26 +- 1e-9
    lane shouldBe empty
  }

  it should "brake before a lower turn-speed limit while preserving bounded deceleration" in {
    val lane = mutable.Queue(VehicleInLane(1, "cars", 0, 50.0 / 3.6, 0, 4.5, exitSpeedLimitMps = Some(5.0)))
    var exit: Option[MicroVehicleUpdate] = None
    var brakedOnApproach = false
    for (tick <- 1L to 30L if lane.nonEmpty) {
      val updates = advance(lane, tick)
      updates.find(_.reachedEnd).foreach(u => exit = Some(u))
      if (lane.nonEmpty && lane.head.position > 85 && lane.head.velocity < 12) brakedOnApproach = true
    }
    brakedOnApproach shouldBe true
    exit shouldBe defined
    exit.get.velocity should be >= 5.0
    exit.get.velocity should be <= 5.45
  }

  it should "start integrating entries after their entry tick regardless of same-tick mailbox order" in {
    val lane = mutable.Queue(VehicleInLane(1L, "cars", 0.0, 0.0, 0.0, 4.5, entryTick = 35L))
    advance(lane, tick = 35L) shouldBe empty
    lane.head.position shouldBe 0.0
    advance(lane, tick = 36L).head.position shouldBe 1.43 +- 1e-9
  }

  it should "preserve distance over repeated fine-clock boundaries instead of discarding each step remainder" in {
    val strategy = DefaultMicroSimulationStrategy()
    val speed = 10.0
    var lane = mutable.Queue(VehicleInLane(1L, "cars", 0.0, speed, 0.0, 4.5))
    var completed = 0
    for (tick <- 1L to 100L) {
      val updates = strategy.executeSubTick(mutable.Map(0 -> lane), 0, tick, 7.3, speed * 3.6,
        0.1, 1, mutable.Map.empty)
      updates.find(_.reachedEnd).foreach { exit =>
        completed += 1
        lane = mutable.Queue(VehicleInLane(1L, "cars", speed * exit.remainingStepSeconds,
          speed, 0.0, 4.5, entryTick = tick))
      }
    }
    completed * 7.3 + lane.head.position shouldBe 100.0 +- 1e-8
  }

  it should "use the leader's rear bumper and its state at the start of the sub-tick" in {
    val lane = mutable.Queue(VehicleInLane(1L, "cars", 50.0, 0.0, 0.0, 12.0),
      VehicleInLane(2L, "cars", 20.0, 0.0, 0.0, 2.0))
    val updates = DefaultMicroSimulationStrategy().executeSubTick(mutable.Map(0 -> lane), 0, 1L,
      100.0, 50.0, 0.1, 1, mutable.Map.empty)
    updates.find(_.vehicleId == 2L).get.gapToLeader shouldBe 18.0
  }

  it should "stop the front bumper at a red stop line without subtracting the follower's length" in {
    val lane = mutable.Queue(VehicleInLane(1L, "cars", 65.0, 10.0, 0.0, 4.5))
    for (tick <- 1L to 30L) {
      advance(lane, tick, signal = Some(model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red))
      lane should have size 1
      lane.head.position should be <= 100.0
    }
    lane.head.position shouldBe 100.0 +- 0.01
    lane.head.velocity shouldBe 0.0 +- 0.01
  }

  it should "keep a vehicle at the stop line under red and authorize its departure only after green" in {
    import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Red, Green }
    val lane = mutable.Queue(VehicleInLane(1L, "cars", 100.0, 0.0, 0.0, 4.5))
    advance(lane, 1L, signal = Some(Red)).head.reachedEnd shouldBe false
    lane should have size 1
    lane.head.velocity shouldBe 0.0
    advance(lane, 2L, signal = Some(Green)).head.reachedEnd shouldBe true
    lane shouldBe empty
  }

  it should "retain the following constraint after the leader enters a connector" in {
    val lane = mutable.Queue(VehicleInLane(13, "cars", 159.3, 0, 0, 4.5))
    val leader = VehicleInLane(12, "cars", 165.826, 0.26, 0, 4.5)
    val update = DefaultMicroSimulationStrategy().executeSubTick(mutable.Map(0 -> lane), 0, 602,
      165.8, 50, 0.1, 1, mutable.Map.empty, downstreamLeaders = Map(13L -> leader)).head
    update.leaderVehicle shouldBe Some(12L)
    update.velocity should be < 0.04
    update.velocity should be > 0.0
  }

  it should "stop at the configured distance before a red stop line" in {
    import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
    val lane = mutable.Queue(VehicleInLane(1, "cars", 65, 10, 0, 4.5))
    for (tick <- 1L to 300L) {
      val update = DefaultMicroSimulationStrategy().executeSubTick(mutable.Map(0 -> lane), 0, tick,
        100, 50, 0.1, 1, mutable.Map.empty, Some(Red), stopLineGapM = 1).head
      update.reachedEnd shouldBe false
      update.position should be <= 99.000001
    }
    lane.head.position shouldBe 99.0 +- 0.01
    lane.head.velocity shouldBe 0.0 +- 0.01
  }

  it should "obey red even when a nearer downstream vehicle is moving away" in {
    import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
    val lane = mutable.Queue(VehicleInLane(13, "cars", 98.95, 0, 0, 4.5))
    val leader = VehicleInLane(12, "cars", 104.6, 13, 0, 4.5)
    val update = DefaultMicroSimulationStrategy().executeSubTick(mutable.Map(0 -> lane), 0, 1,
      100, 50, 0.1, 1, mutable.Map.empty, Some(Red), Map(13L -> leader), stopLineGapM = 1).head
    update.velocity should be < 0.06
    update.position should be < 99.0
  }

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
