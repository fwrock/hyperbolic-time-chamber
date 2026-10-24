package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.BusState
import model.hybrid.entity.state.enumeration.LinkCapacityStateEnum
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Moving, WaitingCapacity, WaitingSignal, WaitingSignalState }
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for the Time Warp `AwaitedReply` wiring ported into `BusSignalHandler` --
  * `Bus`/`Subway` had no wiring of this mechanism at all before this fix, even though
  * `Movable.enterLink`/`leavingLink` (shared by every `Movable` subclass) already registers an
  * awaited reply for their exchanges. `BusSignalHandler`'s `requestSignalState`/`handleLinkAccess`
  * is structurally identical to `CarSignalHandler`'s, so the same reference pattern applies
  * directly -- see `BicycleSignalHandlerSpec`'s doc for the full rationale.
  */
class BusSignalHandlerSpec extends AnyFlatSpec with Matchers {

  private def newBusState(status: model.hybrid.entity.state.enumeration.MovableStatusEnum = WaitingSignalState): BusState = {
    val s = BusState(
      startTick = 0L,
      label = "bus-1",
      capacity = 40,
      busStops = Map.empty,
      numberOfPorts = 2,
      origin = 1L,
      destination = 2L,
      size = 12.0
    )
    s.status = status
    s
  }

  private case class Fixture(
    handler: BusSignalHandler,
    scheduleEventCalls: mutable.ArrayBuffer[Tick],
    outstanding: mutable.Map[String, String],
    consumed: mutable.ArrayBuffer[String]
  )

  private def newFixture(currentTick: Tick = 100L, isTimeWarp: Boolean = true): Fixture = {
    val scheduleEventCalls = mutable.ArrayBuffer.empty[Tick]
    val outstanding = mutable.Map.empty[String, String]
    val consumed = mutable.ArrayBuffer.empty[String]

    val journey = org.interscity.htc.model.hybrid.entity.state.BusJourneyState()
    val journeyReporter = new BusJourneyReporter(
      reportFn = (_, _) => (),
      entityIdFn = () => "htcaid:bus;bus_test",
      currentTickFn = () => currentTick,
      journeyStateFn = () => journey
    )

    val handler = new BusSignalHandler(
      reportFn = (_, _) => (),
      entityIdFn = () => "htcaid:bus;bus_test",
      currentTickFn = () => currentTick,
      journeyReporter = journeyReporter,
      onFinishSpontaneousFn = _ => (),
      scheduleEventFn = tick => scheduleEventCalls += tick,
      onFinishDestructFn = () => (),
      leavingLinkFn = () => (),
      finishJourneyFn = (_, _) => (),
      getCurrentNodeFn = () => 3L,
      getNextLinkFn = () => 100L,
      sendMessageFn = (_, _, _, _) => (),
      logWarnFn = _ => (),
      logDebugFn = _ => (),
      setSignalWaitUntilTickFn = _ => (),
      setSignalWaitNeedsReverifyFn = _ => (),
      restoreRouteIfMissingFn = _ => (),
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
    val state = newBusState(status = Moving)

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L, capacityState = LinkCapacityStateEnum.Available), state)

    state.status shouldBe WaitingSignal
    f.scheduleEventCalls shouldBe mutable.ArrayBuffer(100L)
    f.consumed shouldBe mutable.ArrayBuffer("link-access:100")
  }

  it should "drop a genuinely stale reply when nothing is outstanding for that link" in {
    val f = newFixture()
    val state = newBusState(status = WaitingSignalState)

    f.handler.handleLinkAccess(LinkAccessData(phase = Red, nextTick = 130L), state)

    state.status shouldBe WaitingSignalState
    f.scheduleEventCalls shouldBe empty
    f.consumed shouldBe empty
  }

  it should "not consume the awaited reply on a capacity-Full response" in {
    val f = newFixture()
    f.outstanding.put("link-access:100", "LinkAccessData")
    val state = newBusState(status = WaitingSignalState)

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L, capacityState = LinkCapacityStateEnum.Full), state)

    state.status shouldBe WaitingCapacity
    f.consumed shouldBe empty
    f.outstanding should contain key "link-access:100"
  }

  "handleLinkAccess under conservative mode" should "keep using the unchanged state.status-only guard" in {
    val f = newFixture(isTimeWarp = false)
    val state = newBusState(status = Moving)

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L), state)

    state.status shouldBe Moving
    f.scheduleEventCalls shouldBe empty
    f.consumed shouldBe empty
  }
}
