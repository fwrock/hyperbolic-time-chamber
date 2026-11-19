package org.interscity.htc
package model.hybrid.support.link

import core.entity.event.ActorInteractionEvent
import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import model.hybrid.entity.event.data.*
import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.enumeration.{ActorTypeEnum, SimulationModeEnum}
import model.hybrid.entity.state.model.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** G03: physical admission, not the Node's nominal grant, decides when a handoff completes. */
class MicroMultilaneHandoffSpec extends AnyFlatSpec with Matchers {
  private def link(spillback: Boolean = true) = LinkState(0, from = 1, to = 2, length = 100, lanes = 2,
    speedLimit = 50, capacity = 100, freeSpeed = 50, simulationMode = SimulationModeEnum.MICRO,
    microTicksPerGlobalTick = 1, downstreamLookahead = true, downstreamSpillback = spillback,
    laneConnections = List(LaneConnection(9, 0, 0), LaneConnection(9, 1, 1)),
    microEntry = MicroEntryConfig(enabled = true)).initializeMicroLanes()

  private class Road(var state: LinkState) {
    var tick = 0L
    var scheduled = false
    val entryTicks = mutable.Map.empty[Long, Long]
    val waiting = mutable.Map.empty[Long, Double]
    val sent = mutable.Buffer.empty[(Long, AnyRef)]
    val metrics = new LinkMetricsReporter((_, _) => (), () => "7", () => tick, 60, 1000, () => state,
      entryTicks.get, id => waiting.getOrElse(id, 0.0), () => state.registered.iterator.map(_.actorId).toIterable, _ => ())
    val flow = new LinkVehicleFlowHandler(() => "7", () => tick, (id, _, d, _) => { sent += ((id, d)); () }, _ => (),
      () => state, s => state = s, (id, t) => { entryTicks(id) = t }, entryTicks.get, id => { entryTicks.remove(id); () },
      id => waiting.getOrElseUpdate(id, 0), id => { waiting.remove(id); () }, id => waiting.getOrElse(id, 0),
      () => scheduled, b => scheduled = b, metrics,
      id => state.vehiclesByLane.collectFirst { case (l, q) if q.exists(_.actorId == id) => l }, () => 0, _ => (),
      (_, _) => ())
    def enter(id: Long, lane: Int, seq: Long, position: Double = 0): Unit =
      flow.handleEnterLinkMicro(ActorInteractionEvent(tick, tick, id, "hybrid.actor.Car", id.toString,
        "hybrid.actor.Car", data = "unused", resourceId = "test", seq = seq),
        EnterLinkData(ActorTypeEnum.Car, LoadBalancedDistributed, 5, initialVelocity = 5, nextLinkId = 9,
          initialPosition = position, preferredLane = Some(lane)))
    def acks(id: Long) = sent.collect { case (`id`, d: MicroEnterLinkData) => d }.toList
  }

  "Multi-lane spillback with queued entry" should "be constructible now that physical admission arbitrates" in {
    noException should be thrownBy link()
  }

  it should "hold a handoff in a physically full lane without blocking the other lane or failing" in {
    val road = new Road(link())
    road.enter(1, lane = 0, seq = 1, position = 3) // occupies the head of lane 0
    road.acks(1) should have size 1
    road.enter(2, lane = 0, seq = 2) // physically overlaps vehicle 1: must wait, not throw
    road.enter(3, lane = 1, seq = 3) // other lane is free: admitted at once
    road.acks(2) shouldBe empty
    road.acks(3) should have size 1
    road.state.pendingMicroEntries.map(_.actorId) shouldBe List(2L)
    road.state.vehiclesByLane(0).map(_.actorId).toList shouldBe List(1L)

    road.tick = 4
    road.state.vehiclesByLane(0).clear() // the obstacle leaves physically
    road.flow.drainMicroEntries()
    val ack = road.acks(2).head
    ack.requestSeq shouldBe 2L
    ack.assignedLane shouldBe 0
    ack.initialVelocity shouldBe Some(0.0) // a real wait clears the carried speed
    road.state.pendingMicroEntries shouldBe empty
  }

  it should "admit a delayed handoff exactly once even if drained repeatedly" in {
    val road = new Road(link())
    road.enter(1, lane = 0, seq = 1, position = 3)
    road.enter(2, lane = 0, seq = 2)
    road.tick = 4
    road.state.vehiclesByLane(0).clear()
    road.flow.drainMicroEntries()
    road.flow.drainMicroEntries()
    road.acks(2) should have size 1
    road.state.vehiclesByLane(0).count(_.actorId == 2) shouldBe 1
  }
}
