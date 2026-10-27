package org.interscity.htc
package model.hybrid.support.link

import core.entity.event.ActorInteractionEvent
import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import core.util.JsonUtil
import model.hybrid.entity.event.data.*
import model.hybrid.entity.event.data.link.*
import model.hybrid.entity.event.data.vehicle.*
import model.hybrid.entity.state.{CarState, DriverAttributes, LinkState, NodeState}
import model.hybrid.entity.state.enumeration.{ActorTypeEnum, SimulationModeEnum}
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green
import model.hybrid.entity.state.model.*
import model.hybrid.micro.strategy.DefaultMicroSimulationStrategy
import model.hybrid.support.car.{CarJourneyReporter, CarSignalHandler}
import model.hybrid.support.node.NodeEventHandler
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class LinkSpillbackSpec extends AnyFlatSpec with Matchers {
  private def link = LinkState(0L, from = 1L, to = 2L, length = 100, lanes = 1,
    speedLimit = 50, capacity = 10, freeSpeed = 50, simulationMode = SimulationModeEnum.MICRO,
    microTicksPerGlobalTick = 1, downstreamLookahead = true, downstreamSpillback = true,
    vehiclesByLane = Map(0 -> mutable.Queue(
      VehicleInLane(11, "hybrid.actor.Car", 99.5, 10, 0, 4.5, nextLinkId = 9),
      VehicleInLane(12, "hybrid.actor.Car", 80, 0, 0, 4.5, nextLinkId = 9))))

  private def event(id: Long, seq: Long = 0) = ActorInteractionEvent(1L, 1L, id,
    "hybrid.actor.Car", id.toString, "hybrid.actor.Car", data = "unused", resourceId = "test", seq = seq)

  private def step(s: LinkState, tick: Long) = DefaultMicroSimulationStrategy().executeSubTick(
    mutable.Map.from(s.vehiclesByLane), 0, tick, s.length, s.speedLimit, s.microTimeStep, 1,
    mutable.Map.empty, retainExits = true)

  "A blocked MICRO exit" should "retain one stopped rear bumper and emit only one leave while its follower queues" in {
    val s = link
    step(s, 1).find(_.vehicleId == 11).get.reachedEnd shouldBe true
    s.vehiclesByLane(0).head.pendingExitTick shouldBe Some(1L)
    s.vehiclesByLane(0).head.velocity shouldBe 0.0
    for (tick <- 2L to 300L) {
      step(s, tick).map(_.vehicleId) should not contain 11L
      s.vehiclesByLane(0).head.position shouldBe 100.0
      s.vehiclesByLane(0)(1).gapTo(s.vehiclesByLane(0).head) should be >= (2.0 - 1e-6)
    }
    s.vehiclesByLane(0)(1).velocity should be < 0.01
    s.vehiclesByLane(0) should have size 2
  }

  it should "restore the pending footprint and publish it before replaying a single cleanup without a second leave" in {
    var s = link
    step(s, 1)
    val checkpoint = JsonUtil.toJson(s)
    var sent = Vector.empty[AnyRef]
    val following = new LinkFollowingHandler(() => s, () => 7, () => 3,
      (_, _, d, _) => sent :+= d)
    following.subscribe(6)
    // Boundary publications choose the rear-most vehicle; the retained exit is still lane-owned.
    s.vehiclesByLane(0).dequeueAll(_.actorId == 12)
    following.publish()
    sent.last.asInstanceOf[MicroBoundaryUpdateData].snapshot.vehicle.get.id shouldBe 11L
    s = JsonUtil.fromJson[LinkState](checkpoint)
    s.vehiclesByLane(0).head.pendingExitTick shouldBe Some(1L)
    step(s, 2).map(_.vehicleId) should not contain 11L
    val reporter = new LinkMetricsReporter((_, _) => (), () => "7", () => 3, 60, 10,
      () => s, _ => Some(0L), _ => 0, () => Nil, _ => ())
    s.registered += LinkRegister(11, "hybrid.actor.Car", ActorTypeEnum.Car, LoadBalancedDistributed, 4.5)
    val flow = new LinkVehicleFlowHandler(() => "7", () => 3, (_, _, d, _) => sent :+= d,
      _ => (), () => s, v => s = v, (_, _) => (), _ => Some(0L), _ => (), _ => 0,
      _ => (), _ => 0, () => true, _ => (), reporter, _ => Some(0), () => 0, _ => ())
    sent = Vector.empty
    val leave = LeaveLinkData(ActorTypeEnum.Car, 4.5, LoadBalancedDistributed)
    flow.handleLeaveLink(event(11), leave, wasRegistered = true)
    flow.handleLeaveLink(event(11), leave, wasRegistered = false)
    sent shouldBe Vector(LinkCapacityFreedData(7, 1))
    s.vehiclesByLane(0).map(_.actorId) shouldBe Seq(12L)
  }

  it should "restore a canceled Node wait and grant its original request identity exactly once" in {
    var s = NodeState(0, latitude = 0, longitude = 0, links = List(9L),
      availableCapacity = mutable.Map(9L -> 0))
    var sent = Vector.empty[AnyRef]
    val node = new NodeEventHandler(() => s, () => "2", () => 3, mutable.Map.empty,
      (_, _) => (), (_, _, d, _) => sent :+= d, _ => None, _ => (), _ => ())
    node.handleRequestLinkAccess(event(11, 77), RequestLinkAccessData(9, signalAlreadyCleared = true, approachLinkId = 7))
    val pending = JsonUtil.toJson(s)
    node.handleCancelLinkAccessRequest(event(11), CancelLinkAccessRequestData(9))
    node.handleLinkCapacityFreed(LinkCapacityFreedData(9, 1))
    sent.size shouldBe 1
    s = JsonUtil.fromJson[NodeState](pending)
    sent = Vector.empty
    node.handleLinkCapacityFreed(LinkCapacityFreedData(9, 1))
    sent.map(_.asInstanceOf[model.hybrid.entity.event.node.LinkAccessData].requestSeq) shouldBe Vector(77L)
    s.availableCapacity(9L) shouldBe 0
    s.capacityWaitQueue shouldBe empty
  }

  it should "checkpoint a Car's capacity stop, count its duration once, and clear carried remainder" in {
    var tick = 10L
    var s = CarState(0, origin = 1, destination = 3, actorType = ActorTypeEnum.Car, size = 4.5)
    s.status = model.hybrid.entity.state.enumeration.MovableStatusEnum.WaitingSignalState
    s.journey.secondsPerTick = 0.1
    s.journey.boundaryExitTick = Some(10L)
    s.journey.boundaryRemainderSeconds = 0.04
    s.journey.sumoArrivalSpeed = 10
    s.journey.retainedMicroExitLinkId = Some(7L)
    val reporter = new CarJourneyReporter((_, _) => (), () => "11", () => tick,
      () => Some(1L), () => Some(3L), () => Some(0L), () => DriverAttributes(), () => s.journey)
    var sent = Vector.empty[AnyRef]
    var consumed = Vector.empty[String]
    val handler = new CarSignalHandler((_, _) => (), () => "11", () => tick, reporter,
      _ => (), _ => (), () => (), _ => (), _ => (), (_, _, d, _) => sent :+= d,
      () => 0L, () => 9L, () => Some(3L), _ => (), _ => (), _ => (),
      consumeAwaitedReplyFn = key => consumed :+= key)
    handler.handleLinkAccess(model.hybrid.entity.event.node.LinkAccessData(Green, 10,
      capacityState = model.hybrid.entity.state.enumeration.LinkCapacityStateEnum.Full), s)
    val checkpoint = JsonUtil.toJson(s)
    handler.cancelPendingCapacityRequest(s)
    sent shouldBe Vector(LeaveLinkData(ActorTypeEnum.Car, 4.5, LoadBalancedDistributed))
    s.journey.retainedMicroExitLinkId shouldBe None
    consumed shouldBe Vector("link-access:9")
    s = JsonUtil.fromJson[CarState](checkpoint)
    tick = 35
    handler.handleLinkAccess(model.hybrid.entity.event.node.LinkAccessData(Green, 35), s)
    s.journey.sumoWaitingTimeSeconds shouldBe 2.5
    s.journey.capacityWaitSinceTick shouldBe None
    s.journey.entryRemainderAt(tick) shouldBe 0.0
    handler.handleLinkAccess(model.hybrid.entity.event.node.LinkAccessData(Green, 35), s)
    s.journey.sumoWaitingTimeSeconds shouldBe 2.5
  }

  it should "reject spillback without the supported observation contract" in {
    intercept[IllegalArgumentException](link.copy(downstreamLookahead = false))
    intercept[IllegalArgumentException](link.copy(lanes = 2))
  }

  it should "preserve geometric spacing when admission abruptly stops a moving leader" in {
    val s = link
    s.vehiclesByLane(0).clear()
    s.vehiclesByLane(0).enqueue(
      VehicleInLane(11, "hybrid.actor.Car", 100, 0, 0, 4.5, pendingExitTick = Some(1)),
      VehicleInLane(12, "hybrid.actor.Car", 93.5, 10, 0, 4.5))
    val update = step(s, 2).head
    update.position shouldBe 93.5
    update.velocity shouldBe 0.0
    update.reachedEnd shouldBe false
  }
}
