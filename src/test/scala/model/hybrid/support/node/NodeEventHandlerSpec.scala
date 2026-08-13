package org.interscity.htc
package model.hybrid.support.node

import core.entity.actor.ShardActorId
import core.entity.event.ActorInteractionEvent
import model.hybrid.entity.event.data.link.{ LinkCapacityFreedData, RegisterLinkCapacityData }
import model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import model.hybrid.entity.event.data.vehicle.{ CancelLinkAccessRequestData, RequestLinkAccessData }
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
    connections: mutable.Map[Long, Identify] = mutable.Map.empty,
    approachConnections: mutable.Map[Long, Identify] = mutable.Map.empty,
    signals: mutable.Map[Long, SignalState] = mutable.Map.empty,
    signalWaitingCounts: mutable.Map[Long, Int] = mutable.Map.empty,
    capacityWaitQueue: mutable.Map[Long, mutable.Queue[PendingLinkAccessRequest]] = mutable.Map.empty,
    availableCapacity: mutable.Map[Long, Int] = mutable.Map.empty
  ): NodeState =
    NodeState(
      startTick = 0L,
      latitude = 0.0,
      longitude = 0.0,
      links = List.empty,
      connections = connections,
      approachConnections = approachConnections,
      signals = signals,
      signalWaitingCounts = signalWaitingCounts,
      capacityWaitQueue = capacityWaitQueue,
      availableCapacity = availableCapacity
    )

  private def newHandler(
    state: NodeState,
    getLinkDependencyFn: Long => Option[ShardActorId] = _ => None
  ): (NodeEventHandler, mutable.ArrayBuffer[(Long, String, AnyRef, String)]) = {
    val sent = mutable.ArrayBuffer.empty[(Long, String, AnyRef, String)]
    val handler = new NodeEventHandler(
      getStateFn = () => state,
      entityIdFn = () => "htcaid:node;n_test",
      currentTickFn = () => 100L,
      pendingSignals = mutable.Map.empty,
      reportFn = (_, _) => (),
      sendMessageFn = (id, shard, data, evType) => sent += ((id, shard, data, evType)),
      getLinkDependencyFn = getLinkDependencyFn,
      logWarnFn = _ => (),
      logDebugFn = _ => ()
    )
    (handler, sent)
  }

  private def requestEvent(carId: Long = 1L): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = 100L,
      lamportTick = 100L,
      actorRefId = carId,
      shardRefId = "hybrid.actor.Car",
      actorPathRef = carId.toString,
      actorClassType = "hybrid.actor.Car",
      data = "unused",
      resourceId = "res-1"
    )

  "handleRequestLinkAccess" should "reply with a Green fallback when there is no connection entry for the link (uncontrolled intersection)" in {
    val (handler, sent) = newHandler(newState())

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = 999L))

    sent should have size 1
    val (_, _, data, evType) = sent.head
    evType shouldBe "ReceiveLinkAccess"
    data shouldBe LinkAccessData(phase = Green, nextTick = 100L)
  }

  it should "reply with a Green fallback when the connection exists but no signal is registered for it" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val (handler, sent) = newHandler(newState(connections = connections))

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = 100L))

    sent should have size 1
    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
  }

  it should "reply with a Green fallback and still send a response when state is not yet initialized" in {
    val sent = mutable.ArrayBuffer.empty[(Long, String, AnyRef, String)]
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

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = 100L))

    sent should have size 1
    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
  }

  it should "reply Red with queuePosition 0 for the first request while the signal is Red, incrementing signalWaitingCounts" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map.empty[Long, Int]
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = 100L))

    sent should have size 1
    sent.head._3 shouldBe LinkAccessData(phase = Red, nextTick = 130L, queuePosition = 0)
    waitingCounts(100L) shouldBe 1
  }

  it should "reply Green/Available with queuePosition 0 and clear signalWaitingCounts when the signal is Green and capacity is unknown (permissive default)" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Green, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map(100L -> 3)
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestLinkAccess(requestEvent(), RequestLinkAccessData(targetLinkId = 100L))

    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
    waitingCounts.get(100L) shouldBe None
  }

  it should "document that signalWaitingCounts has no per-request decrement: N distinct RequestLinkAccessData for the same Red link inflate queuePosition to N-1, N in total" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map.empty[Long, Int]
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestLinkAccess(requestEvent(3L), RequestLinkAccessData(targetLinkId = 100L))
    handler.handleRequestLinkAccess(requestEvent(3L), RequestLinkAccessData(targetLinkId = 100L))
    handler.handleRequestLinkAccess(requestEvent(3L), RequestLinkAccessData(targetLinkId = 100L))

    sent.map(_._3.asInstanceOf[LinkAccessData].queuePosition) shouldBe Seq(0, 1, 2)
    waitingCounts(100L) shouldBe 3
  }

  // ── Capacity: fresh requests ─────────────────────────────────────────────────────────────

  it should "grant Green/Available and decrement availableCapacity when capacity is known and positive" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map(100L -> 2)
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, availableCapacity = availableCapacity))

    handler.handleRequestLinkAccess(requestEvent(1L), RequestLinkAccessData(targetLinkId = 100L))

    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
    availableCapacity(100L) shouldBe 1
  }

  it should "buffer the requester FIFO and reply Green/Full when availableCapacity is exhausted" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map(100L -> 0)
    val capacityWaitQueue = mutable.Map.empty[Long, mutable.Queue[PendingLinkAccessRequest]]
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleRequestLinkAccess(requestEvent(1L), RequestLinkAccessData(targetLinkId = 100L))

    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L, capacityState = Full)
    capacityWaitQueue(100L) shouldBe mutable.Queue(PendingLinkAccessRequest(actorRefId = 1L, shardRefId = "hybrid.actor.Car"))
    availableCapacity(100L) shouldBe 0
  }

  it should "queue vehicles FIFO by arrival order regardless of which approach link/shard they requested from -- fairness across competing movements comes for free from a single per-destination-link buffer, not from any per-origin logic" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map(100L -> 0)
    val capacityWaitQueue = mutable.Map.empty[Long, mutable.Queue[PendingLinkAccessRequest]]
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    def requestFrom(carId: Long, shardRefId: String): ActorInteractionEvent =
      requestEvent(carId).copy(shardRefId = shardRefId, actorClassType = shardRefId)


    handler.handleRequestLinkAccess(requestFrom(4L, "hybrid.actor.Car"), RequestLinkAccessData(targetLinkId = 100L))
    handler.handleRequestLinkAccess(requestFrom(5L, "hybrid.actor.Bus"), RequestLinkAccessData(targetLinkId = 100L))

    capacityWaitQueue(100L) shouldBe mutable.Queue(
      PendingLinkAccessRequest(actorRefId = 4L, shardRefId = "hybrid.actor.Car"),
      PendingLinkAccessRequest(actorRefId = 5L, shardRefId = "hybrid.actor.Bus")
    )

    handler.handleLinkCapacityFreed(LinkCapacityFreedData(linkId = 100L, freedCount = 1))

    sent.last shouldBe ((4L, "hybrid.actor.Car", LinkAccessData(phase = Green, nextTick = 100L, capacityState = Available), "ReceiveLinkAccess"))
    capacityWaitQueue(100L) shouldBe mutable.Queue(PendingLinkAccessRequest(actorRefId = 5L, shardRefId = "hybrid.actor.Bus"))
  }

  // ── Capacity: cancellation (destroyed while buffered) ────────────────────────────────────

  "handleCancelLinkAccessRequest" should "remove the matching entry from capacityWaitQueue, and the link entirely once it's the last one" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map(100L -> 0)
    val capacityWaitQueue = mutable.Map(
      100L -> mutable.Queue(
        PendingLinkAccessRequest(actorRefId = 1L, shardRefId = "hybrid.actor.Car"),
        PendingLinkAccessRequest(actorRefId = 2L, shardRefId = "hybrid.actor.Car")
      )
    )
    val (handler, _) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleCancelLinkAccessRequest(requestEvent(1L), CancelLinkAccessRequestData(targetLinkId = 100L))
    capacityWaitQueue(100L) shouldBe mutable.Queue(PendingLinkAccessRequest(actorRefId = 2L, shardRefId = "hybrid.actor.Car"))

    handler.handleCancelLinkAccessRequest(requestEvent(2L), CancelLinkAccessRequestData(targetLinkId = 100L))
    capacityWaitQueue.get(100L) shouldBe None
  }

  it should "be a no-op when there is no matching entry (already dequeued, or never existed) or when state is not yet initialized" in {
    val capacityWaitQueue = mutable.Map(100L -> mutable.Queue(PendingLinkAccessRequest(actorRefId = 2L, shardRefId = "hybrid.actor.Car")))
    val (handler, _) = newHandler(newState(capacityWaitQueue = capacityWaitQueue))

    handler.handleCancelLinkAccessRequest(requestEvent(1L), CancelLinkAccessRequestData(targetLinkId = 100L))
    capacityWaitQueue(100L) should have size 1

    handler.handleCancelLinkAccessRequest(requestEvent(1L), CancelLinkAccessRequestData(targetLinkId = 999L))
    capacityWaitQueue.get(999L) shouldBe None

    noException should be thrownBy {
      val nullStateHandler = new NodeEventHandler(
        getStateFn = () => null,
        entityIdFn = () => "htcaid:node;n_test",
        currentTickFn = () => 100L,
        pendingSignals = mutable.Map.empty,
        reportFn = (_, _) => (),
        sendMessageFn = (_, _, _, _) => (),
        getLinkDependencyFn = _ => None,
        logWarnFn = _ => (),
        logDebugFn = _ => ()
      )
      nullStateHandler.handleCancelLinkAccessRequest(requestEvent(1L), CancelLinkAccessRequestData(targetLinkId = 100L))
    }
  }

  it should "never buffer or check capacity for a Red reply" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val availableCapacity = mutable.Map(100L -> 0)
    val capacityWaitQueue = mutable.Map.empty[Long, mutable.Queue[PendingLinkAccessRequest]]
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleRequestLinkAccess(requestEvent(1L), RequestLinkAccessData(targetLinkId = 100L))

    sent.head._3 shouldBe LinkAccessData(phase = Red, nextTick = 130L, queuePosition = 0)
    capacityWaitQueue.get(100L) shouldBe None
    availableCapacity(100L) shouldBe 0
  }

  // ── Capacity: registration ───────────────────────────────────────────────────────────────

  "handleRegisterLinkCapacity" should "seed availableCapacity for the link, verified via a subsequent request" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map.empty[Long, Int]
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, availableCapacity = availableCapacity))

    handler.handleRegisterLinkCapacity(RegisterLinkCapacityData(linkId = 100L, capacity = 1))
    availableCapacity(100L) shouldBe 1

    handler.handleRequestLinkAccess(requestEvent(1L), RequestLinkAccessData(targetLinkId = 100L))
    sent.head._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L)
    availableCapacity(100L) shouldBe 0

    handler.handleRequestLinkAccess(requestEvent(2L), RequestLinkAccessData(targetLinkId = 100L))
    sent(1)._3 shouldBe LinkAccessData(phase = Green, nextTick = 100L, capacityState = Full)
  }

  // ── Capacity: freed-slot notification and draining ───────────────────────────────────────

  "handleLinkCapacityFreed" should "increment availableCapacity and grant buffered vehicles FIFO while the signal is Green" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    val availableCapacity = mutable.Map(100L -> 0)
    val capacityWaitQueue = mutable.Map(
      100L -> mutable.Queue(
        PendingLinkAccessRequest(actorRefId = 1L, shardRefId = "hybrid.actor.Car"),
        PendingLinkAccessRequest(actorRefId = 2L, shardRefId = "hybrid.actor.Car")
      )
    )
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleLinkCapacityFreed(LinkCapacityFreedData(linkId = 100L, freedCount = 1))

    sent should have size 1
    sent.head shouldBe ((1L, "hybrid.actor.Car", LinkAccessData(phase = Green, nextTick = 100L, capacityState = Available), "ReceiveLinkAccess"))
    capacityWaitQueue(100L) shouldBe mutable.Queue(PendingLinkAccessRequest(actorRefId = 2L, shardRefId = "hybrid.actor.Car"))
    availableCapacity(100L) shouldBe 0
  }

  it should "not drain the buffer while the relevant signal is currently Red (checked against Node's own local state, no message)" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map(500L -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val availableCapacity = mutable.Map(100L -> 0)
    val capacityWaitQueue = mutable.Map(
      100L -> mutable.Queue(PendingLinkAccessRequest(actorRefId = 1L, shardRefId = "hybrid.actor.Car"))
    )
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleLinkCapacityFreed(LinkCapacityFreedData(linkId = 100L, freedCount = 1))

    sent shouldBe empty
    availableCapacity(100L) shouldBe 1 // still incremented, just not drained yet
    capacityWaitQueue(100L) should have size 1
  }

  it should "drain the buffer once the relevant signal turns Green (second drain trigger, via handleReceiveSignalChangeStatus)" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map.empty[Long, SignalState]
    val availableCapacity = mutable.Map(100L -> 1)
    val capacityWaitQueue = mutable.Map(
      100L -> mutable.Queue(PendingLinkAccessRequest(actorRefId = 1L, shardRefId = "hybrid.actor.Car"))
    )
    val (handler, sent) = newHandler(
      newState(connections = connections, signals = signals, availableCapacity = availableCapacity, capacityWaitQueue = capacityWaitQueue)
    )

    handler.handleReceiveSignalChangeStatus(
      requestEvent(),
      TrafficSignalChangeStatusData(
        signalState = SignalState(state = Green, remainingTime = 0L, nextTick = 160L),
        nextTick = 160L,
        phaseOrigin = 500L
      )
    )

    sent.exists(_._3 == LinkAccessData(phase = Green, nextTick = 100L, capacityState = Available)) shouldBe true
    capacityWaitQueue.get(100L) shouldBe None
  }

  "handleReceiveSignalChangeStatus" should "drain signalWaitingCounts for every link keyed to the phase's origin when it turns Green" in {
    val connections = mutable.Map(100L -> Identify(id = 500L), 101L -> Identify(id = 500L))
    val signals = mutable.Map.empty[Long, SignalState]
    val waitingCounts = mutable.Map(100L -> 5, 101L -> 2, 102L -> 9)
    val (handler, _) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleReceiveSignalChangeStatus(
      requestEvent(),
      TrafficSignalChangeStatusData(
        signalState = SignalState(state = Green, remainingTime = 0L, nextTick = 160L),
        nextTick = 160L,
        phaseOrigin = 500L
      )
    )

    waitingCounts.get(100L) shouldBe None
    waitingCounts.get(101L) shouldBe None
    waitingCounts(102L) shouldBe 9
  }

  it should "not touch signalWaitingCounts when the phase turns Red" in {
    val connections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map.empty[Long, SignalState]
    val waitingCounts = mutable.Map(100L -> 4)
    val (handler, _) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleReceiveSignalChangeStatus(
      requestEvent(),
      TrafficSignalChangeStatusData(
        signalState = SignalState(state = Red, remainingTime = 30L, nextTick = 190L),
        nextTick = 190L,
        phaseOrigin = 500L
      )
    )

    waitingCounts(100L) shouldBe 4
  }

  it should "notify the APPROACH link (from approachConnections), not the outgoing link (from connections), of a phase change" in {
    val connections = mutable.Map(103L -> Identify(id = 500L)) // outgoing edge
    val approachConnections = mutable.Map(100L -> Identify(id = 500L)) // approach edge
    val signals = mutable.Map.empty[Long, SignalState]
    val dependencies = mutable.Map(
      100L -> ShardActorId(entityId = 100L, classType = "hybrid.actor.Link", shardBucket = "res-ab"),
      103L -> ShardActorId(entityId = 103L, classType = "hybrid.actor.Link", shardBucket = "res-bc")
    )
    val (handler, sent) = newHandler(
      newState(connections = connections, approachConnections = approachConnections, signals = signals),
      getLinkDependencyFn = dependencies.get
    )

    handler.handleReceiveSignalChangeStatus(
      requestEvent(),
      TrafficSignalChangeStatusData(
        signalState = SignalState(state = Red, remainingTime = 30L, nextTick = 190L),
        nextTick = 190L,
        phaseOrigin = 500L
      )
    )

    val notifiedEntityIds = sent.filter(_._4 == "LinkSignalState").map(_._1)
    notifiedEntityIds shouldBe Seq(100L)
    notifiedEntityIds should not contain 103L
  }

  it should "still drain signalWaitingCounts/capacityWaitQueue by the OUTGOING link (connections) even though notification uses approachConnections" in {
    val connections = mutable.Map(103L -> Identify(id = 500L))
    val approachConnections = mutable.Map(100L -> Identify(id = 500L))
    val signals = mutable.Map.empty[Long, SignalState]
    val waitingCounts = mutable.Map(103L -> 5)
    val (handler, _) = newHandler(
      newState(connections = connections, approachConnections = approachConnections, signals = signals, signalWaitingCounts = waitingCounts)
    )

    handler.handleReceiveSignalChangeStatus(
      requestEvent(),
      TrafficSignalChangeStatusData(
        signalState = SignalState(state = Green, remainingTime = 0L, nextTick = 160L),
        nextTick = 160L,
        phaseOrigin = 500L
      )
    )

    waitingCounts.get(103L) shouldBe None
  }
}
