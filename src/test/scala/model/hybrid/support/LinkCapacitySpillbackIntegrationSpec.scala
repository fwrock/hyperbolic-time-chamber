package org.interscity.htc
package model.hybrid.support

import core.entity.actor.ShardActorId
import core.entity.event.ActorInteractionEvent
import core.types.Tick
import model.hybrid.entity.event.data.link.{ LinkCapacityFreedData, RegisterLinkCapacityData }
import model.hybrid.entity.event.data.vehicle.RequestLinkAccessData
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import model.hybrid.entity.state.enumeration.LinkCapacityStateEnum.{ Available, Full }
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{ WaitingCapacity, WaitingSignal }
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green
import model.hybrid.entity.state.model.SignalState
import model.hybrid.entity.state.{ CarState, DriverAttributes, NodeState }
import model.hybrid.support.car.{ CarJourneyReporter, CarSignalHandler }
import model.hybrid.support.node.NodeEventHandler

import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** End-to-end coverage of the congestion-propagation link-capacity protocol
  * (docs/CONGESTION_PROPAGATION_DESIGN.md), wiring the real production `NodeEventHandler` and
  * `CarSignalHandler` together directly (no Pekko `ActorSystem`) — the same reasoning as
  * `CarSignalHandlerSpec`'s doc comment for why a full actor-level test isn't used here:
  * `CarSignalHandler.requestSignalState`'s message-send branch reads the JVM-wide `CityMapUtil`
  * singleton, which throws without a seeded `city_map.json` in this shared test JVM. This test
  * starts from the point *after* a request was already sent (constructing `RequestLinkAccessData`/
  * `LinkAccessData` directly, exactly as the real handlers do), and drives both sides' real
  * `handle*` methods to prove the full round trip — Fase 1 (buffered while Full) and Fase 2 (freed
  * slot -> FIFO grant) — works end-to-end, not just within each handler in isolation.
  */
class LinkCapacitySpillbackIntegrationSpec extends AnyFlatSpec with Matchers {

  private val targetLinkId = "link_bc"
  private val signalId     = "signal_bc"

  private def newNodeState(): NodeState =
    NodeState(
      startTick = 0L,
      latitude = 0.0,
      longitude = 0.0,
      links = List.empty,
      connections = mutable.Map(targetLinkId -> Identify(id = signalId)),
      signals = mutable.Map(signalId -> SignalState(state = Green, remainingTime = 0L, nextTick = 100L))
    )

  private case class NodeFixture(handler: NodeEventHandler, sent: mutable.ArrayBuffer[(String, String, AnyRef, String)], state: NodeState)

  private def newNodeFixture(): NodeFixture = {
    val state = newNodeState()
    val sent  = mutable.ArrayBuffer.empty[(String, String, AnyRef, String)]
    val handler = new NodeEventHandler(
      getStateFn = () => state,
      entityIdFn = () => "htcaid:node;n_bc",
      currentTickFn = () => 100L,
      pendingSignals = mutable.Map.empty,
      reportFn = (_, _) => (),
      sendMessageFn = (id, shard, data, evType) => sent += ((id, shard, data, evType)),
      getLinkDependencyFn = _ => None,
      logWarnFn = _ => (),
      logDebugFn = _ => ()
    )
    NodeFixture(handler, sent, state)
  }

  private def requestEvent(carId: String): ActorInteractionEvent =
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

  private case class CarFixture(
    handler: CarSignalHandler,
    state: CarState,
    scheduleEventCalls: mutable.ArrayBuffer[Tick],
    leavingLinkCalls: mutable.ArrayBuffer[Unit]
  )

  private def newCarFixture(carId: String): CarFixture = {
    val state = CarState(
      startTick = 0L,
      origin = "n_origin",
      destination = "n_dest",
      actorType = ActorTypeEnum.Car,
      size = 4.5
    )
    state.status = model.hybrid.entity.state.enumeration.MovableStatusEnum.WaitingSignalState

    val scheduleEventCalls = mutable.ArrayBuffer.empty[Tick]
    val leavingLinkCalls   = mutable.ArrayBuffer.empty[Unit]

    val journeyReporter = new CarJourneyReporter(
      reportFn = (_, _) => (),
      entityIdFn = () => carId,
      currentTickFn = () => 100L,
      tripOriginFn = () => Some("n_origin"),
      tripDestFn = () => Some("n_dest"),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => DriverAttributes()
    )

    val handler = new CarSignalHandler(
      reportFn = (_, _) => (),
      entityIdFn = () => carId,
      currentTickFn = () => 100L,
      journeyReporter = journeyReporter,
      onFinishSpontaneousFn = _ => (),
      scheduleEventFn = tick => scheduleEventCalls += tick,
      leavingLinkFn = () => leavingLinkCalls += (()),
      selfDestructFn = () => (),
      isPersonCentricFn = () => false,
      logWarnFn = _ => (),
      logStaleEventDebugFn = _ => (),
      sendMessageFn = (_, _, _, _) => (),
      getCurrentNodeFn = () => "n_bc",
      getNextLinkFn = () => targetLinkId,
      getTripDestinationFn = () => Some("n_dest"),
      setSignalWaitUntilTickFn = _ => (),
      setSignalWaitNeedsReverifyFn = _ => (),
      onSignalWaitFn = _ => ()
    )

    CarFixture(handler, state, scheduleEventCalls, leavingLinkCalls)
  }

  "the link-capacity spillback protocol" should "buffer a second car when the link is full, then grant it FIFO once the first car's departure frees a slot" in {
    val node = newNodeFixture()
    node.handler.handleRegisterLinkCapacity(RegisterLinkCapacityData(linkId = targetLinkId, capacity = 1))

    val car1 = newCarFixture("car_1")
    val car2 = newCarFixture("car_2")

    // --- Fase 1: car_1 requests and is granted immediately (capacity 1 -> 0) ---
    node.handler.handleRequestLinkAccess(requestEvent("car_1"), RequestLinkAccessData(targetLinkId = targetLinkId))
    val car1Reply = node.sent.last._3.asInstanceOf[LinkAccessData]
    car1Reply shouldBe LinkAccessData(phase = Green, nextTick = 100L, capacityState = Available)

    car1.handler.handleLinkAccess(car1Reply, car1.state)
    car1.state.status shouldBe WaitingSignal
    car1.scheduleEventCalls shouldBe mutable.ArrayBuffer(100L)

    // --- Fase 1: car_2 requests next, link is now full (capacity 0) -> buffered ---
    node.handler.handleRequestLinkAccess(requestEvent("car_2"), RequestLinkAccessData(targetLinkId = targetLinkId))
    val car2Reply = node.sent.last._3.asInstanceOf[LinkAccessData]
    car2Reply shouldBe LinkAccessData(phase = Green, nextTick = 100L, capacityState = Full)

    car2.handler.handleLinkAccess(car2Reply, car2.state)
    car2.state.status shouldBe WaitingCapacity
    // Genuinely dormant: no scheduleEvent call at all while buffered.
    car2.scheduleEventCalls shouldBe empty
    car2.leavingLinkCalls shouldBe empty

    // --- Fase 2: car_1 eventually leaves the link, freeing exactly one slot ---
    node.handler.handleLinkCapacityFreed(LinkCapacityFreedData(linkId = targetLinkId, freedCount = 1))

    val grantMessage = node.sent.last
    grantMessage._1 shouldBe "car_2"
    val car2Grant = grantMessage._3.asInstanceOf[LinkAccessData]
    car2Grant shouldBe LinkAccessData(phase = Green, nextTick = 100L, capacityState = Available)

    // --- car_2 processes the unsolicited grant exactly like an initial Green reply ---
    car2.handler.handleLinkAccess(car2Grant, car2.state)
    car2.state.status shouldBe WaitingSignal
    car2.scheduleEventCalls shouldBe mutable.ArrayBuffer(100L)
  }
}
