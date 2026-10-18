package org.interscity.htc
package model.hybrid.support.motorcycle

import core.types.Tick
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.MotorcycleState
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import model.hybrid.entity.state.enumeration.LinkCapacityStateEnum
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Moving, WaitingCapacity, WaitingSignal, WaitingSignalState }
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for the Time Warp `AwaitedReply` wiring ported into
  * `MotorcycleSignalHandler` -- see `BicycleSignalHandlerSpec`'s doc for the full rationale (same
  * `CarSignalHandler` reference pattern, same two core proofs).
  */
class MotorcycleSignalHandlerSpec extends AnyFlatSpec with Matchers {

  private def newMotorcycleState(status: model.hybrid.entity.state.enumeration.MovableStatusEnum = WaitingSignalState): MotorcycleState = {
    val s = MotorcycleState(startTick = 0L, origin = 1L, destination = 2L, actorType = ActorTypeEnum.Motorcycle, size = 1.2)
    s.status = status
    s
  }

  private case class Fixture(
    handler: MotorcycleSignalHandler,
    scheduleEventCalls: mutable.ArrayBuffer[Tick],
    outstanding: mutable.Map[String, String],
    consumed: mutable.ArrayBuffer[String]
  )

  private def newFixture(currentTick: Tick = 100L, isTimeWarp: Boolean = true): Fixture = {
    val scheduleEventCalls = mutable.ArrayBuffer.empty[Tick]
    val outstanding = mutable.Map.empty[String, String]
    val consumed = mutable.ArrayBuffer.empty[String]

    val journeyReporter = new MotorcycleJourneyReporter(
      reportFn = (_, _) => (),
      entityIdFn = () => "htcaid:motorcycle;moto_test",
      currentTickFn = () => currentTick,
      tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => model.hybrid.entity.state.DriverAttributes()
    )

    val handler = new MotorcycleSignalHandler(
      reportFn = (_, _) => (),
      entityIdFn = () => "htcaid:motorcycle;moto_test",
      currentTickFn = () => currentTick,
      journeyReporter = journeyReporter,
      onFinishSpontaneousFn = _ => (),
      scheduleEventFn = tick => scheduleEventCalls += tick,
      leavingLinkFn = () => (),
      selfDestructFn = () => (),
      isPersonCentricFn = () => false,
      logDebugFn = _ => (),
      sendMessageFn = (_, _, _, _) => (),
      getCurrentNodeFn = () => 3L,
      getNextLinkFn = () => 100L,
      getTripDestinationFn = () => Some(2L),
      finishJourneyFn = (_, _) => (),
      onFinishPrivateVehicleFn = _ => (),
      aggressivenessFn = () => 0.5,
      setSignalWaitUntilTickFn = _ => (),
      setSignalWaitNeedsReverifyFn = _ => (),
      isTimeWarpFn = () => isTimeWarp,
      registerAwaitedReplyFn = (key, kind) => outstanding.put(key, kind),
      isAwaitingReplyFn = (key, kind, _) => outstanding.get(key).contains(kind),
      consumeAwaitedReplyFn = key => { consumed += key; outstanding.remove(key) }
    )

    Fixture(handler, scheduleEventCalls, outstanding, consumed)
  }

  "handleLinkAccess under Time Warp" should "accept a genuine reply even though a rollback restored state.status away from WaitingSignalState/WaitingCapacity" in {
    val f = newFixture()
    f.outstanding.put("link-access:100", "LinkAccessData")
    val state = newMotorcycleState(status = Moving)

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L, capacityState = LinkCapacityStateEnum.Available), state)

    state.status shouldBe WaitingSignal
    f.scheduleEventCalls shouldBe mutable.ArrayBuffer(100L)
    f.consumed shouldBe mutable.ArrayBuffer("link-access:100")
  }

  it should "drop a genuinely stale reply when nothing is outstanding for that link" in {
    val f = newFixture()
    val state = newMotorcycleState(status = WaitingSignalState)

    f.handler.handleLinkAccess(LinkAccessData(phase = Red, nextTick = 130L), state)

    state.status shouldBe WaitingSignalState
    f.scheduleEventCalls shouldBe empty
    f.consumed shouldBe empty
  }

  it should "not consume the awaited reply on a capacity-Full response" in {
    val f = newFixture()
    f.outstanding.put("link-access:100", "LinkAccessData")
    val state = newMotorcycleState(status = WaitingSignalState)

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L, capacityState = LinkCapacityStateEnum.Full), state)

    state.status shouldBe WaitingCapacity
    f.consumed shouldBe empty
    f.outstanding should contain key "link-access:100"
  }

  "handleLinkAccess under conservative mode" should "keep using the unchanged state.status-only guard" in {
    val f = newFixture(isTimeWarp = false)
    val state = newMotorcycleState(status = Moving)

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L), state)

    state.status shouldBe Moving
    f.scheduleEventCalls shouldBe empty
    f.consumed shouldBe empty
  }
}
