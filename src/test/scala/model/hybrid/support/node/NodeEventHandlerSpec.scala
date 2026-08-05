package org.interscity.htc
package model.hybrid.support.node

import core.entity.actor.ShardActorId
import core.entity.event.ActorInteractionEvent
import model.hybrid.entity.event.data.link.{ LinkCapacityFreedData, RegisterLinkCapacityData }
import model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import model.hybrid.entity.event.data.vehicle.RequestLinkAccessData
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.NodeState
import model.hybrid.entity.state.enumeration.LinkCapacityStateEnum.{ Available, Full }
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import model.hybrid.entity.state.model.{ PendingLinkAccessRequest, SignalState }

import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for docs/KNOWN_GAPS.md's "+161-167s signal-wait overshoot" finding, and
  * for the congestion-propagation link-capacity feature (docs/CONGESTION_PROPAGATION_DESIGN.md).
  *
  * The signal-wait fix removed a Car/Bus/Bicycle/Motorcycle-side retry that resent
  * `RequestLinkAccessData` every tick a reply hadn't yet arrived — but that fix only holds if its
  * premise is true: [[NodeEventHandler.handleRequestLinkAccess]] must reply on *every* branch,
  * with no code path that silently drops the request. These tests lock that invariant in, and
  * separately document `signalWaitingCounts`' actual (Node-side) increment/reset semantics — a
  * bare per-request counter with no per-request decrement, only a bulk reset on Green — since
  * that's precisely what turned repeated requests from a single car into a corrupted,
  * ever-growing queue position.
  */
class NodeEventHandlerSpec extends AnyFlatSpec with Matchers {

  private def newState(
    connections: mutable.Map[String, Identify] = mutable.Map.empty,
    signals: mutable.Map[String, SignalState] = mutable.Map.empty,
    signalWaitingCounts: mutable.Map[String, Int] = mutable.Map.empty,
    capacityWaitQueue: mutable.Map[String, mutable.Queue[PendingLinkAccessRequest]] = mutable.Map.empty,
    availableCapacity: mutable.Map[String, Int] = mutable.Map.empty
  ): NodeState =
    NodeState(
      startTick = 0L,
      latitude = 0.0,
      longitude = 0.0,
      links = List.empty,
      connections = connections,
      signals = signals,
      signalWaitingCounts = signalWaitingCounts,
      capacityWaitQueue = capacityWaitQueue,
      availableCapacity = availableCapacity
    )

  private def newHandler(state: NodeState): (NodeEventHandler, mutable.ArrayBuffer[(String, String, AnyRef, String)]) = {
    val sent = mutable.ArrayBuffer.empty[(String, String, AnyRef, String)]
    val handler = new NodeEventHandler(
      getStateFn = () => state,
      entityIdFn = () => "htcaid:node;n_test",
      currentTickFn = () => 100L,
      pendingSignals = mutable.Map.empty,
      reportFn = (_, _) => (),
      sendMessageFn = (id, shard, data, evType) => sent += ((id, shard, data, evType)),
      getLinkDependencyFn = _ => None,
      logWarnFn = _ => (),
      logDebugFn = _ => ()
    )
    (handler, sent)
  }

  private def requestEvent(carId: String = "htcaid:car;car_1"): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = 100L,
      lamportTick = 100L,
      actorRefId = carId,
      shardRefId = "hybrid.actor.Car",
      actorPathRef = carId,
      actorClassType = "hybrid.actor.Car",
      data = "unused",
      resourceId = "res-1"
    )

  "handleRequestLinkAccess" should "reply with a Green fallback when there is no connection entry for the link (uncontrolled intersection)" in {
    val (handler, sent) = newHandler(newState())

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = "link_unknown"))

    sent should have size 1
    val (_, _, data, evType) = sent.head
    evType shouldBe "ReceiveLinkAccess"
    data shouldBe LinkAccessData(phase = Green, nextTick = 100L)
  }

  it should "reply with a Green fallback when the connection exists but no signal is registered for it" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val (handler, sent) = newHandler(newState(connections = connections))

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = "link_ab"))

    sent should have size 1
    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
  }

  it should "reply with a Green fallback and still send a response when state is not yet initialized" in {
    val sent = mutable.ArrayBuffer.empty[(String, String, AnyRef, String)]
    val handler = new NodeEventHandler(
      getStateFn = () => null,
      entityIdFn = () => "htcaid:node;n_test",
      currentTickFn = () => 100L,
      pendingSignals = mutable.Map.empty,
      reportFn = (_, _) => (),
      sendMessageFn = (id, shard, data, evType) => sent += ((id, shard, data, evType)),
      getLinkDependencyFn = _ => None,
      logWarnFn = _ => (),
      logDebugFn = _ => ()
    )

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = "link_ab"))

    sent should have size 1
    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
  }

  it should "reply Red with queuePosition 0 for the first request while the signal is Red, incrementing signalWaitingCounts" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map.empty[String, Int]
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = "link_ab"))

    sent should have size 1
    sent.head._3 shouldBe LinkAccessData(phase = Red, nextTick = 130L, queuePosition = 0)
    waitingCounts("link_ab") shouldBe 1
  }

  it should "reply Green/Available with queuePosition 0 and clear signalWaitingCounts when the signal is Green and capacity is unknown (permissive default)" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Green, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map("link_ab" -> 3)
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = "link_ab"))

    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
    waitingCounts.get("link_ab") shouldBe None
  }

  it should "document that signalWaitingCounts has no per-request decrement: N distinct RequestLinkAccessData for the same Red link inflate queuePosition to N-1, N in total" in {
    // This is the load-bearing invariant behind the vehicle-side fix: signalWaitingCounts only
    // ever resets in bulk on Green (see handleReceiveSignalChangeStatus below), never per
    // request. Before the fix, WaitingSignalState retried by resending RequestLinkAccessData
    // every tick a reply hadn't arrived — each resend landed here and permanently inflated this
    // count, corrupting queuePosition for the retrying car AND every car queued behind it. The
    // fix stops the vehicle from ever sending more than one request per approach; this test
    // exists so that if that discipline is ever violated again, the resulting queuePosition
    // inflation is visible and expected, not a mystery to re-diagnose from scratch.
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map.empty[String, Int]
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestLinkAccess(requestEvent("car_a"), RequestLinkAccessData(targetLinkId = "link_ab"))
    handler.handleRequestLinkAccess(requestEvent("car_a"), RequestLinkAccessData(targetLinkId = "link_ab"))
    handler.handleRequestLinkAccess(requestEvent("car_a"), RequestLinkAccessData(targetLinkId = "link_ab"))

    sent.map(_._3.asInstanceOf[LinkAccessData].queuePosition) shouldBe Seq(0, 1, 2)
    waitingCounts("link_ab") shouldBe 3
  }

  // ── Capacity: fresh requests ─────────────────────────────────────────────────────────────

  it should "grant Green/Available and decrement availableCapacity when capacity is known and positive" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map("link_ab" -> 2)
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, availableCapacity = availableCapacity))

    handler.handleRequestLinkAccess(requestEvent("car_1"), RequestLinkAccessData(targetLinkId = "link_ab"))

    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
    availableCapacity("link_ab") shouldBe 1
  }

  it should "buffer the requester FIFO and reply Green/Full when availableCapacity is exhausted" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map("link_ab" -> 0)
    val capacityWaitQueue = mutable.Map.empty[String, mutable.Queue[PendingLinkAccessRequest]]
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleRequestLinkAccess(requestEvent("car_1"), RequestLinkAccessData(targetLinkId = "link_ab"))

    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L, capacityState = Full)
    capacityWaitQueue("link_ab") shouldBe mutable.Queue(PendingLinkAccessRequest(actorRefId = "car_1", shardRefId = "hybrid.actor.Car"))
    availableCapacity("link_ab") shouldBe 0
  }

  it should "queue vehicles FIFO by arrival order regardless of which approach link/shard they requested from -- fairness across competing movements comes for free from a single per-destination-link buffer, not from any per-origin logic" in {
    // See docs/CONGESTION_PROPAGATION_DESIGN.md's Fairness section: capacityWaitQueue is keyed
    // only by targetLinkId, never by the requester's origin link/shard -- two cars converging on
    // the same destination link from different approaches are ordered purely by request arrival
    // order, with no special-casing needed. Priority/right-of-way between movements remains
    // explicitly deferred; this test locks in that plain FIFO fairness (not starvation, not
    // origin-based bias) already holds without it.
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map("link_ab" -> 0)
    val capacityWaitQueue = mutable.Map.empty[String, mutable.Queue[PendingLinkAccessRequest]]
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    def requestFrom(carId: String, shardRefId: String): ActorInteractionEvent =
      requestEvent(carId).copy(shardRefId = shardRefId, actorClassType = shardRefId)

    // car_north approaches via a different shard/actor class than car_west, but both want the
    // same destination link.
    handler.handleRequestLinkAccess(requestFrom("car_north", "hybrid.actor.Car"), RequestLinkAccessData(targetLinkId = "link_ab"))
    handler.handleRequestLinkAccess(requestFrom("car_west", "hybrid.actor.Bus"), RequestLinkAccessData(targetLinkId = "link_ab"))

    capacityWaitQueue("link_ab") shouldBe mutable.Queue(
      PendingLinkAccessRequest(actorRefId = "car_north", shardRefId = "hybrid.actor.Car"),
      PendingLinkAccessRequest(actorRefId = "car_west", shardRefId = "hybrid.actor.Bus")
    )

    // One slot frees -- the FIRST arrival (car_north) is granted, not car_west, even though
    // nothing in the request distinguished their approach direction.
    handler.handleLinkCapacityFreed(LinkCapacityFreedData(linkId = "link_ab", freedCount = 1))

    sent.last shouldBe (("car_north", "hybrid.actor.Car", LinkAccessData(phase = Green, nextTick = 100L, capacityState = Available), "ReceiveLinkAccess"))
    capacityWaitQueue("link_ab") shouldBe mutable.Queue(PendingLinkAccessRequest(actorRefId = "car_west", shardRefId = "hybrid.actor.Bus"))
  }

  it should "never buffer or check capacity for a Red reply" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val availableCapacity = mutable.Map("link_ab" -> 0)
    val capacityWaitQueue = mutable.Map.empty[String, mutable.Queue[PendingLinkAccessRequest]]
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleRequestLinkAccess(requestEvent("car_1"), RequestLinkAccessData(targetLinkId = "link_ab"))

    sent.head._3 shouldBe LinkAccessData(phase = Red, nextTick = 130L, queuePosition = 0)
    capacityWaitQueue.get("link_ab") shouldBe None
    availableCapacity("link_ab") shouldBe 0
  }

  // ── Capacity: registration ───────────────────────────────────────────────────────────────

  "handleRegisterLinkCapacity" should "seed availableCapacity for the link, verified via a subsequent request" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map.empty[String, Int]
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, availableCapacity = availableCapacity))

    handler.handleRegisterLinkCapacity(RegisterLinkCapacityData(linkId = "link_ab", capacity = 1))
    availableCapacity("link_ab") shouldBe 1

    handler.handleRequestLinkAccess(requestEvent("car_1"), RequestLinkAccessData(targetLinkId = "link_ab"))
    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
    availableCapacity("link_ab") shouldBe 0

    handler.handleRequestLinkAccess(requestEvent("car_2"), RequestLinkAccessData(targetLinkId = "link_ab"))
    sent(1)._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L, capacityState = Full)
  }

  // ── Capacity: freed-slot notification and draining ───────────────────────────────────────

  "handleLinkCapacityFreed" should "increment availableCapacity and grant buffered vehicles FIFO while the signal is Green" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map("link_ab" -> 0)
    val capacityWaitQueue = mutable.Map(
      "link_ab" -> mutable.Queue(
        PendingLinkAccessRequest(actorRefId = "car_1", shardRefId = "hybrid.actor.Car"),
        PendingLinkAccessRequest(actorRefId = "car_2", shardRefId = "hybrid.actor.Car")
      )
    )
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleLinkCapacityFreed(LinkCapacityFreedData(linkId = "link_ab", freedCount = 1))

    sent should have size 1
    sent.head shouldBe (("car_1", "hybrid.actor.Car", LinkAccessData(phase = Green, nextTick = 100L, capacityState = Available), "ReceiveLinkAccess"))
    capacityWaitQueue("link_ab") shouldBe mutable.Queue(PendingLinkAccessRequest(actorRefId = "car_2", shardRefId = "hybrid.actor.Car"))
    availableCapacity("link_ab") shouldBe 0
  }

  it should "not drain the buffer while the relevant signal is currently Red (checked against Node's own local state, no message)" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val availableCapacity = mutable.Map("link_ab" -> 0)
    val capacityWaitQueue = mutable.Map(
      "link_ab" -> mutable.Queue(PendingLinkAccessRequest(actorRefId = "car_1", shardRefId = "hybrid.actor.Car"))
    )
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleLinkCapacityFreed(LinkCapacityFreedData(linkId = "link_ab", freedCount = 1))

    sent shouldBe empty
    availableCapacity("link_ab") shouldBe 1 // still incremented, just not drained yet
    capacityWaitQueue("link_ab") should have size 1
  }

  it should "drain the buffer once the relevant signal turns Green (second drain trigger, via handleReceiveSignalChangeStatus)" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map.empty[String, SignalState]
    val availableCapacity = mutable.Map("link_ab" -> 1)
    val capacityWaitQueue = mutable.Map(
      "link_ab" -> mutable.Queue(PendingLinkAccessRequest(actorRefId = "car_1", shardRefId = "hybrid.actor.Car"))
    )
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleReceiveSignalChangeStatus(
      requestEvent(),
      TrafficSignalChangeStatusData(
        signalState = SignalState(state = Green, remainingTime = 0L, nextTick = 160L),
        nextTick = 160L,
        phaseOrigin = "signal_1"
      )
    )

    sent.exists(_._3 == LinkAccessData(phase = Green, nextTick = 100L, capacityState = Available)) shouldBe true
    capacityWaitQueue.get("link_ab") shouldBe None
  }

  "handleReceiveSignalChangeStatus" should "drain signalWaitingCounts for every link keyed to the phase's origin when it turns Green" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"), "link_cd" -> Identify(id = "signal_1"))
    val signals = mutable.Map.empty[String, SignalState]
    val waitingCounts = mutable.Map("link_ab" -> 5, "link_cd" -> 2, "link_unrelated" -> 9)
    val (handler, _) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleReceiveSignalChangeStatus(
      requestEvent(),
      TrafficSignalChangeStatusData(
        signalState = SignalState(state = Green, remainingTime = 0L, nextTick = 160L),
        nextTick = 160L,
        phaseOrigin = "signal_1"
      )
    )

    waitingCounts.get("link_ab") shouldBe None
    waitingCounts.get("link_cd") shouldBe None
    waitingCounts("link_unrelated") shouldBe 9
  }

  it should "not touch signalWaitingCounts when the phase turns Red" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map.empty[String, SignalState]
    val waitingCounts = mutable.Map("link_ab" -> 4)
    val (handler, _) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleReceiveSignalChangeStatus(
      requestEvent(),
      TrafficSignalChangeStatusData(
        signalState = SignalState(state = Red, remainingTime = 30L, nextTick = 190L),
        nextTick = 190L,
        phaseOrigin = "signal_1"
      )
    )

    waitingCounts("link_ab") shouldBe 4
  }
}
