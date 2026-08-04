package org.interscity.htc
package model.hybrid.support.node

import core.entity.actor.ShardActorId
import core.entity.event.ActorInteractionEvent
import model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import model.hybrid.entity.event.node.SignalStateData
import model.hybrid.entity.state.NodeState
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import model.hybrid.entity.state.model.SignalState

import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for docs/KNOWN_GAPS.md's "+161-167s signal-wait overshoot" finding.
  *
  * The fix removed a Car/Bus/Bicycle/Motorcycle-side retry that resent `RequestSignalStateData`
  * every tick a reply hadn't yet arrived — but that fix only holds if its premise is true:
  * [[NodeEventHandler.handleRequestSignalState]] must reply on *every* branch, with no code path
  * that silently drops the request. These tests lock that invariant in, and separately document
  * `signalWaitingCounts`' actual (Node-side) increment/reset semantics — a bare per-request
  * counter with no per-request decrement, only a bulk reset on Green — since that's precisely
  * what turned repeated requests from a single car into a corrupted, ever-growing queue position.
  */
class NodeEventHandlerSpec extends AnyFlatSpec with Matchers {

  private def newState(
    connections: mutable.Map[String, Identify] = mutable.Map.empty,
    signals: mutable.Map[String, SignalState] = mutable.Map.empty,
    signalWaitingCounts: mutable.Map[String, Int] = mutable.Map.empty
  ): NodeState =
    NodeState(
      startTick = 0L,
      latitude = 0.0,
      longitude = 0.0,
      links = List.empty,
      connections = connections,
      signals = signals,
      signalWaitingCounts = signalWaitingCounts
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

  "handleRequestSignalState" should "reply with a Green fallback when there is no connection entry for the link (uncontrolled intersection)" in {
    val (handler, sent) = newHandler(newState())

    handler.handleRequestSignalState(requestEvent(), RequestSignalStateData(targetLinkId = "link_unknown"))

    sent should have size 1
    val (_, _, data, evType) = sent.head
    evType shouldBe "ReceiveSignalState"
    data shouldBe SignalStateData(phase = Green, nextTick = 100L)
  }

  it should "reply with a Green fallback when the connection exists but no signal is registered for it" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val (handler, sent) = newHandler(newState(connections = connections))

    handler.handleRequestSignalState(requestEvent(), RequestSignalStateData(targetLinkId = "link_ab"))

    sent should have size 1
    sent.head._3 shouldBe SignalStateData(phase = Green, nextTick = 100L)
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

    handler.handleRequestSignalState(requestEvent(), RequestSignalStateData(targetLinkId = "link_ab"))

    sent should have size 1
    sent.head._3 shouldBe SignalStateData(phase = Green, nextTick = 100L)
  }

  it should "reply Red with queuePosition 0 for the first request while the signal is Red, incrementing signalWaitingCounts" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map.empty[String, Int]
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestSignalState(requestEvent(), RequestSignalStateData(targetLinkId = "link_ab"))

    sent should have size 1
    sent.head._3 shouldBe SignalStateData(phase = Red, nextTick = 130L, queuePosition = 0)
    waitingCounts("link_ab") shouldBe 1
  }

  it should "reply Green with queuePosition 0 and clear signalWaitingCounts when the signal is Green" in {
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Green, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map("link_ab" -> 3)
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestSignalState(requestEvent(), RequestSignalStateData(targetLinkId = "link_ab"))

    sent.head._3 shouldBe SignalStateData(phase = Green, nextTick = 130L, queuePosition = 0)
    waitingCounts.get("link_ab") shouldBe None
  }

  it should "document that signalWaitingCounts has no per-request decrement: N distinct RequestSignalStateData for the same Red link inflate queuePosition to N-1, N in total" in {
    // This is the load-bearing invariant behind the vehicle-side fix: signalWaitingCounts only
    // ever resets in bulk on Green (see handleReceiveSignalChangeStatus below), never per
    // request. Before the fix, WaitingSignalState retried by resending RequestSignalStateData
    // every tick a reply hadn't arrived — each resend landed here and permanently inflated this
    // count, corrupting queuePosition for the retrying car AND every car queued behind it. The
    // fix stops the vehicle from ever sending more than one request per approach; this test
    // exists so that if that discipline is ever violated again, the resulting queuePosition
    // inflation is visible and expected, not a mystery to re-diagnose from scratch.
    val connections = mutable.Map("link_ab" -> Identify(id = "signal_1"))
    val signals = mutable.Map("signal_1" -> SignalState(state = Red, remainingTime = 30L, nextTick = 130L))
    val waitingCounts = mutable.Map.empty[String, Int]
    val (handler, sent) = newHandler(newState(connections = connections, signals = signals, signalWaitingCounts = waitingCounts))

    handler.handleRequestSignalState(requestEvent("car_a"), RequestSignalStateData(targetLinkId = "link_ab"))
    handler.handleRequestSignalState(requestEvent("car_a"), RequestSignalStateData(targetLinkId = "link_ab"))
    handler.handleRequestSignalState(requestEvent("car_a"), RequestSignalStateData(targetLinkId = "link_ab"))

    sent.map(_._3.asInstanceOf[SignalStateData].queuePosition) shouldBe Seq(0, 1, 2)
    waitingCounts("link_ab") shouldBe 3
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
