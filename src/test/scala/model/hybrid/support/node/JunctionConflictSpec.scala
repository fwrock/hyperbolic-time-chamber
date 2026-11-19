package org.interscity.htc
package model.hybrid.support.node

import core.entity.event.ActorInteractionEvent
import core.util.JsonUtil
import model.hybrid.entity.event.data.link.{LinkCapacityFreedData, LinkSignalStateData, SubscribeMicroBoundaryData}
import model.hybrid.entity.event.data.vehicle.RequestLinkAccessData
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.NodeState
import model.hybrid.entity.state.enumeration.LinkCapacityStateEnum
import model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{Green, Red}
import model.hybrid.entity.state.model.{SignalState, MovementSignalBinding, GapAcceptance, JunctionConflict, JunctionMovement, MicroBoundarySnapshot, MicroBoundaryVehicle, MicroLaneBoundary}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** G04: crossing connectors exclude each other; priority and age order the waiters. */
class JunctionConflictSpec extends AnyFlatSpec with Matchers {
  // Movement 1->2 uses connector 20 (a yielding left turn); movement 3->4 uses connector 30 (priority through).
  private val movements = List(JunctionMovement(1, 2, 20), JunctionMovement(3, 4, 30), JunctionMovement(5, 6, 40))
  private def node(conflicts: List[JunctionConflict]) = NodeState(0, latitude = 0, longitude = 0, links = List(1, 2, 3, 4, 5, 6),
    junctionMovements = movements, conflicts = conflicts,
    availableCapacity = mutable.Map(20L -> 5, 30L -> 5, 40L -> 5))
  private val priority30 = List(JunctionConflict(20, 30, Some(30), "test-rule"))
  private val noPriority = List(JunctionConflict(20, 30, None, "test-rule"))

  private class Harness(var s: NodeState) {
    var tick = 1L
    val raw = mutable.Buffer.empty[(Long, AnyRef)]
    def sent = raw.collect { case (id, a: LinkAccessData) => (id, a) }
    def aspects = raw.collect { case (id, d: LinkSignalStateData) => (id, d.phase, d.targetLinkId) }.toList
    def subscriptions = raw.collect { case (id, _: SubscribeMicroBoundaryData) => id }.toList
    val handler = new NodeEventHandler(() => s, () => "9", () => tick, mutable.Map.empty, (_, _) => (),
      (id, _, d, _) => { raw += ((id, d)); () }, id => Some(core.entity.actor.ShardActorId(id, "hybrid.actor.Link")), _ => (), _ => ())
    def event(id: Long, seq: Long) = ActorInteractionEvent(tick, tick, id, "hybrid.actor.Car", id.toString,
      "hybrid.actor.Car", data = "unused", resourceId = "test", seq = seq)
    def request(vehicle: Long, approach: Long, target: Long): Unit =
      handler.handleRequestLinkAccess(event(vehicle, vehicle), RequestLinkAccessData(target, signalAlreadyCleared = true,
        approachLinkId = approach))
    def free(connector: Long): Unit = handler.handleLinkCapacityFreed(LinkCapacityFreedData(connector, 1))
    def grants = sent.collect { case (v, a) if a.junctionLink.nonEmpty => (v, a.junctionLink.get.linkId) }.toList
    def waits = sent.collect { case (v, a) if a.capacityState == LinkCapacityStateEnum.Full => v }.toList
  }

  "Crossing connectors" should "exclude each other and release the waiter when the occupant leaves" in {
    val h = new Harness(node(priority30))
    h.request(11, 1, 2)
    h.grants shouldBe List((11L, 20L))
    h.request(12, 3, 4) // connector 20 is occupied: must wait even though it has priority
    h.waits shouldBe List(12L)
    h.free(20)
    h.grants shouldBe List((11L, 20L), (12L, 30L))
    h.free(20)
    h.grants should have size 2 // no duplicate grant
  }

  it should "let a waiting priority request overtake a later lower-priority one" in {
    val h = new Harness(node(priority30))
    h.request(11, 1, 2) // occupies 20
    h.tick = 2; h.request(12, 3, 4) // priority, waits
    h.tick = 3; h.request(13, 1, 2) // arrives later; a higher-priority conflicting request is pending
    h.waits shouldBe List(12L, 13L)
    h.free(20)
    h.grants.map(_._1) shouldBe List(11L, 12L) // 12 first; 13 still blocked by 12's occupancy
    h.free(30)
    h.grants.map(_._1) shouldBe List(11L, 12L, 13L)
  }

  it should "serve equal-priority conflicts by request age, not by arrival at the connector" in {
    val h = new Harness(node(noPriority))
    h.request(11, 1, 2)
    h.tick = 2; h.request(12, 3, 4) // older waiter on 30
    h.tick = 3; h.request(13, 1, 2) // younger on the same connector as the occupant: still waits for 12
    h.waits shouldBe List(12L, 13L)
    h.free(20)
    h.grants.map(_._1) shouldBe List(11L, 12L)
  }

  it should "not affect connectors that have no conflict rule" in {
    val h = new Harness(node(priority30))
    h.request(11, 1, 2)
    h.request(14, 5, 6)
    h.grants.map(_._1) shouldBe List(11L, 14L)
  }

  it should "survive a JSON checkpoint with occupancy and waiters" in {
    val h = new Harness(node(priority30))
    h.request(11, 1, 2)
    h.request(12, 3, 4)
    val restored = JsonUtil.fromJson[NodeState](JsonUtil.toJson(h.s))
    restored.conflicts shouldBe priority30
    restored.occupiedConnectors(20L) shouldBe 1
    val h2 = new Harness(restored)
    h2.free(20)
    h2.grants.map(_._1) shouldBe List(12L)
  }

  it should "require a named source and known connectors" in {
    an[IllegalArgumentException] should be thrownBy JunctionConflict(20, 30, None, " ")
    an[IllegalArgumentException] should be thrownBy node(List(JunctionConflict(20, 99, None, "x")))
    an[IllegalArgumentException] should be thrownBy JunctionConflict(20, 30, Some(40), "x")
  }

  // G05: connector 30 (priority) is fed by approach link 3 (length 100 m); its conflict point is 10 m past the stop line.
  private val gapRule = List(JunctionConflict(20, 30, Some(30), "test-rule", Some(GapAcceptance(4.0, 10.0))))
  private def snapshot(tick: Long, vehicle: Option[MicroBoundaryVehicle]) = MicroBoundarySnapshot(3, tick, tick + 1, 100, vehicle,
    laneBoundaries = List(MicroLaneBoundary(0, vehicle, vehicle)))
  private def far = Some(MicroBoundaryVehicle(70, 0.0, 10.0, 4.5)) // 110 m away at 10 m/s
  private def near = Some(MicroBoundaryVehicle(71, 95.0, 10.0, 4.5)) // 15 m away: arrives in about 1.3 s

  "Gap acceptance" should "treat a missing observation as unknown, subscribe once, and grant when the gap is observed" in {
    val h = new Harness(node(gapRule))
    h.tick = 2; h.request(11, 1, 2)
    h.waits shouldBe List(11L)
    h.tick = 3; h.request(12, 1, 2)
    h.subscriptions shouldBe List(3L) // exactly one subscription despite two evaluations
    h.tick = 4
    h.handler.handleBoundaryUpdate(3, snapshot(3, far))
    h.grants.map(_._1) shouldBe List(11L, 12L) // same movement: one observed gap releases both (documented limitation)
  }

  it should "hold a crossing while a priority vehicle is too close and release it when the road clears" in {
    val h = new Harness(node(gapRule))
    h.tick = 2; h.request(11, 1, 2)
    h.tick = 3; h.handler.handleBoundaryUpdate(3, snapshot(2, near))
    h.grants shouldBe empty
    h.tick = 4; h.handler.handleBoundaryUpdate(3, snapshot(3, None))
    h.grants.map(_._1) shouldBe List(11L)
  }

  it should "credit the time elapsed since the observation to the approaching vehicle" in {
    val h = new Harness(node(gapRule))
    h.tick = 2
    h.handler.handleBoundaryUpdate(3, snapshot(0, None)) // ignored until requested
    h.request(11, 1, 2) // requests the observation
    h.handler.handleBoundaryUpdate(3, snapshot(1, Some(MicroBoundaryVehicle(72, 27.5, 10.0, 4.5)))) // about 5 s away at tick 1
    h.tick = 3
    h.free(20) // re-evaluate at tick 3 (0.2 s later): still accepted
    h.grants.map(_._1) shouldBe List(11L)
    h.tick = 40
    h.request(12, 1, 2) // 3.9 s after the observation: effective arrival about 1.1 s
    h.free(20)
    h.grants.map(_._1) shouldBe List(11L)
    h.waits should contain(12L)
  }

  it should "ignore a priority vehicle already waiting at the stop line and never test the priority movement itself" in {
    val h = new Harness(node(gapRule))
    h.tick = 2; h.request(11, 1, 2)
    h.tick = 3; h.handler.handleBoundaryUpdate(3, snapshot(2, Some(MicroBoundaryVehicle(73, 100.0, 0.0, 4.5, pendingExit = true))))
    h.grants.map(_._1) shouldBe List(11L)
    val h2 = new Harness(node(gapRule))
    h2.request(12, 3, 4)
    h2.grants shouldBe List((12L, 30L)) // priority movement: no gap test, no observation needed
    h2.subscriptions shouldBe empty
  }

  it should "survive a checkpoint with its observations and subscription" in {
    val h = new Harness(node(gapRule))
    h.tick = 2; h.request(11, 1, 2)
    h.handler.handleBoundaryUpdate(3, snapshot(2, near))
    val restored = JsonUtil.fromJson[NodeState](JsonUtil.toJson(h.s))
    restored.observationsRequested shouldBe List(3L)
    restored.boundaryObservations(3L).snapshots.map(_.tick) shouldBe List(2L)
    val h2 = new Harness(restored)
    h2.tick = 5; h2.handler.handleBoundaryUpdate(3, snapshot(4, None))
    h2.grants.map(_._1) shouldBe List(11L)
    h2.subscriptions shouldBe empty // restored Node does not subscribe again
  }

  it should "reject gap acceptance without a priority or with invalid parameters" in {
    an[IllegalArgumentException] should be thrownBy JunctionConflict(20, 30, None, "x", Some(GapAcceptance(4, 10)))
    an[IllegalArgumentException] should be thrownBy GapAcceptance(0, 10)
    an[IllegalArgumentException] should be thrownBy GapAcceptance(4, -1)
  }

  "A red priority movement" should "not block a yielding crossing, and releases its waiter when it turns red" in {
    val base = node(gapRule)
    val withSignal = base.copy(movementSignals = List(MovementSignalBinding(3, 4, 801)))
    withSignal.signals(801L) = SignalState(Green, 10, 100)
    val h = new Harness(withSignal)
    h.tick = 2; h.request(11, 1, 2)
    h.tick = 3; h.handler.handleBoundaryUpdate(3, snapshot(2, near)) // a priority car 1.3 s away with a green signal
    h.grants shouldBe empty
    h.tick = 4
    h.handler.handleReceiveSignalChangeStatus(h.event(801, 801), TrafficSignalChangeStatusData(SignalState(Red, 50, 200), 200, 801))
    h.grants.map(_._1) shouldBe List(11L) // the priority approach is red: its queue is no gap threat
  }

  it should "hold the crossing again when the priority movement is green with a vehicle too close" in {
    val withSignal = node(gapRule).copy(movementSignals = List(MovementSignalBinding(3, 4, 801)))
    withSignal.signals(801L) = SignalState(Red, 50, 200)
    val h = new Harness(withSignal)
    h.tick = 2; h.request(11, 1, 2)
    h.tick = 3; h.handler.handleBoundaryUpdate(3, snapshot(2, near))
    h.grants.map(_._1) shouldBe List(11L) // red priority: granted at once
    h.request(12, 1, 2)
    h.free(20)
    h.handler.handleReceiveSignalChangeStatus(h.event(801, 801), TrafficSignalChangeStatusData(SignalState(Green, 50, 300), 300, 801))
    h.tick = 4; h.request(13, 1, 2)
    h.waits should contain(13L) // now green with a near vehicle: unknown/near means wait
  }

  "Follow-up gap" should "require a larger gap for each vehicle of the yielding movement already admitted" in {
    val rule = List(JunctionConflict(20, 30, Some(30), "test-rule", Some(GapAcceptance(3.0, 10.0, followUpSeconds = 2.0))))
    val h = new Harness(node(rule))
    h.tick = 2; h.request(11, 1, 2); h.request(12, 1, 2)
    h.tick = 4 // a priority vehicle about 4 s away at the snapshot
    h.handler.handleBoundaryUpdate(3, snapshot(3, Some(MicroBoundaryVehicle(80, 49.2, 10.0, 4.5))))
    h.grants.map(_._1) shouldBe List(11L) // 3.9 s >= 3.0 for the first, but the second needs 3.0 + 2.0 = 5.0 s
    h.waits should contain(12L)
    h.tick = 5 // the clear road was observed at tick 4: only an observation strictly before the decision tick counts
    h.handler.handleBoundaryUpdate(3, snapshot(4, None)) // the road clears: the follower may go
    h.grants.map(_._1) shouldBe List(11L, 12L)
  }

  it should "keep one gap per queue when the follow-up is zero (previous behaviour) and reject a negative value" in {
    an[IllegalArgumentException] should be thrownBy GapAcceptance(3.0, 10.0, followUpSeconds = -1.0)
    GapAcceptance(3.0, 10.0).followUpSeconds shouldBe 0.0
  }

  "Published permission (anticipatory braking)" should "tell the yielding approach, only on change, whether a vehicle arriving now would be granted" in {
    val rule = List(JunctionConflict(20, 30, Some(30), "test-rule", Some(GapAcceptance(3.0, 10.0, publishPermission = true))))
    val h = new Harness(node(rule))
    h.tick = 3; h.handler.handleRegisterLinkCapacity(model.hybrid.entity.event.data.link.RegisterLinkCapacityData(20, 5)) // no-op for aspects
    h.request(11, 1, 2) // subscribes to the priority approach; no observation yet: unknown, so not granted
    h.tick = 4; h.handler.handleBoundaryUpdate(3, snapshot(3, near))
    h.aspects shouldBe List((1L, Red, Some(2L))) // a priority vehicle is about to arrive: the yielding approach must stop at the line
    h.tick = 5; h.handler.handleBoundaryUpdate(3, snapshot(3, near)) // same situation: no repeated publication
    h.aspects should have size 1
    h.tick = 6; h.handler.handleBoundaryUpdate(3, snapshot(5, None)) // the road is clear
    h.aspects.last shouldBe ((1L, Green, Some(2L)))
    h.aspects should have size 2
  }

  it should "AND the permission with the movement's own signal and stay off unless enabled" in {
    val rule = List(JunctionConflict(20, 30, Some(30), "test-rule", Some(GapAcceptance(3.0, 10.0, publishPermission = true))))
    val withSignal = node(rule).copy(movementSignals = List(MovementSignalBinding(1, 2, 801)))
    withSignal.signals(801L) = SignalState(Green, 10, 100)
    val h = new Harness(withSignal)
    h.tick = 3; h.request(11, 1, 2)
    h.tick = 4; h.handler.handleBoundaryUpdate(3, snapshot(3, near))
    h.aspects.last._2 shouldBe Red
    h.handler.handleReceiveSignalChangeStatus(h.event(801, 801), TrafficSignalChangeStatusData(SignalState(Green, 20, 300), 300, 801))
    h.aspects.last._2 shouldBe Red // own signal Green but the conflict forbids it: the approach still sees Red
    val off = new Harness(node(gapRule)) // publishPermission defaults to false
    off.tick = 3; off.request(11, 1, 2); off.tick = 4; off.handler.handleBoundaryUpdate(3, snapshot(3, near))
    off.aspects shouldBe empty
  }
}
