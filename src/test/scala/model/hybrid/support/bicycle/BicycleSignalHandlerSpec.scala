package org.interscity.htc
package model.hybrid.support.bicycle

import core.types.Tick
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.BicycleState
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import model.hybrid.entity.state.enumeration.LinkCapacityStateEnum
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Moving, WaitingCapacity, WaitingSignal, WaitingSignalState }
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for the Time Warp `AwaitedReply` wiring ported into `BicycleSignalHandler`
  * (mirrors `CarSignalHandlerSpec`/`CarSignalHandler`'s reference implementation, and
  * `SimulationBaseActorAwaitedReplySpec`'s two core proofs): a rollback-orphaned reply must still
  * be accepted when it's genuinely outstanding under Time Warp (even if `state.status` was rolled
  * back to something other than `WaitingSignalState`/`WaitingCapacity`), while a genuinely stale
  * reply -- superseded by a fresh request, or with nothing outstanding at all -- is still dropped.
  */
class BicycleSignalHandlerSpec extends AnyFlatSpec with Matchers {

  private def newBicycleState(status: model.hybrid.entity.state.enumeration.MovableStatusEnum = WaitingSignalState): BicycleState = {
    val s = BicycleState(startTick = 0L, origin = 1L, destination = 2L, actorType = ActorTypeEnum.Bicycle, size = 1.8)
    s.status = status
    s
  }

  private case class Fixture(
    handler: BicycleSignalHandler,
    finishCalls: mutable.ArrayBuffer[Option[Tick]],
    scheduleEventCalls: mutable.ArrayBuffer[Tick],
    registered: mutable.ArrayBuffer[(String, String)],
    outstanding: mutable.Map[String, String],
    consumed: mutable.ArrayBuffer[String]
  )

  private def newFixture(currentTick: Tick = 100L, isTimeWarp: Boolean = true): Fixture = {
    val finishCalls = mutable.ArrayBuffer.empty[Option[Tick]]
    val scheduleEventCalls = mutable.ArrayBuffer.empty[Tick]
    val registered = mutable.ArrayBuffer.empty[(String, String)]
    val outstanding = mutable.Map.empty[String, String]
    val consumed = mutable.ArrayBuffer.empty[String]

    val journeyReporter = new BicycleJourneyReporter(
      reportFn = (_, _) => (),
      entityIdFn = () => "htcaid:bicycle;bike_test",
      currentTickFn = () => currentTick,
      tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => model.hybrid.entity.state.DriverAttributes()
    )

    val handler = new BicycleSignalHandler(
      reportFn = (_, _) => (),
      entityIdFn = () => "htcaid:bicycle;bike_test",
      currentTickFn = () => currentTick,
      journeyReporter = journeyReporter,
      onFinishSpontaneousFn = tick => finishCalls += tick,
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
      setSignalWaitUntilTickFn = _ => (),
      setSignalWaitNeedsReverifyFn = _ => (),
      isTimeWarpFn = () => isTimeWarp,
      registerAwaitedReplyFn = (key, kind) => { registered += ((key, kind)); outstanding.put(key, kind) },
      isAwaitingReplyFn = (key, kind, _) => outstanding.get(key).contains(kind),
      consumeAwaitedReplyFn = key => { consumed += key; outstanding.remove(key) }
    )

    Fixture(handler, finishCalls, scheduleEventCalls, registered, outstanding, consumed)
  }

  "handleLinkAccess under Time Warp" should "accept a genuine reply even though a rollback restored state.status away from WaitingSignalState/WaitingCapacity" in {
    val f = newFixture()
    // The request that would have been sent by requestSignalState -- registered directly, as the
    // real call site would via registerAwaitedReplyFn.
    f.outstanding.put("link-access:100", "LinkAccessData")
    val state = newBicycleState(status = Moving) // rollback restored this away from WaitingSignalState

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L, capacityState = LinkCapacityStateEnum.Available), state)

    state.status shouldBe WaitingSignal
    f.scheduleEventCalls shouldBe mutable.ArrayBuffer(100L)
    f.consumed shouldBe mutable.ArrayBuffer("link-access:100")
  }

  it should "drop a genuinely stale reply when nothing is outstanding for that link" in {
    val f = newFixture()
    val state = newBicycleState(status = WaitingSignalState) // status alone would look genuine

    f.handler.handleLinkAccess(LinkAccessData(phase = Red, nextTick = 130L), state)

    state.status shouldBe WaitingSignalState // unchanged -- never accepted
    f.scheduleEventCalls shouldBe empty
    f.consumed shouldBe empty
  }

  it should "not consume the awaited reply on a capacity-Full response (the node sends a later, unsolicited grant for the same key)" in {
    val f = newFixture()
    f.outstanding.put("link-access:100", "LinkAccessData")
    val state = newBicycleState(status = WaitingSignalState)

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L, capacityState = LinkCapacityStateEnum.Full), state)

    state.status shouldBe WaitingCapacity
    f.consumed shouldBe empty
    f.outstanding should contain key "link-access:100"
  }

  "handleLinkAccess under conservative mode" should "keep using the unchanged state.status-only guard" in {
    val f = newFixture(isTimeWarp = false)
    val state = newBicycleState(status = Moving)

    f.handler.handleLinkAccess(LinkAccessData(phase = Green, nextTick = 100L), state)

    state.status shouldBe Moving // dropped -- conservative guard never consults isAwaitingReplyFn
    f.scheduleEventCalls shouldBe empty
    f.consumed shouldBe empty
  }
}
