package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import model.hybrid.entity.event.node.SignalStateData
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, WaitingSignal, WaitingSignalState }
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import model.hybrid.entity.state.{ CarState, DriverAttributes }
import model.hybrid.entity.state.enumeration.ActorTypeEnum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for docs/KNOWN_GAPS.md's "+161-167s signal-wait overshoot" finding, and
  * for the follow-up fix that replaced `deferFinishSpontaneous()` with a genuine
  * deregister-then-`scheduleEvent` re-entry.
  *
  * `requestSignalState`'s message-send branch reads `CityMapUtil.nodesById` — a JVM-wide
  * singleton with an eager `lazy val` that loads `city_map.json` from disk and throws when it's
  * absent, with no seam to fake it in this shared test JVM (see `RaptorMultiModalEngineSpec`'s doc
  * comment for the established precedent on why that branch isn't unit-tested directly here).
  * What *is* fully testable, and is where the fix's behavior lives, is
  * [[CarSignalHandler.handleSignalState]] — the reply-side half of the exchange. It now NEVER
  * calls `onFinishSpontaneousFn` directly: the car fully deregistered from the TimeManager (a real
  * `onFinishSpontaneous(None)`, not a deferred safety-net suppression) before this reply could
  * ever arrive, so there's no pending spontaneous event left to resolve here. Instead it always
  * calls `scheduleEventFn(waitUntilTick)` — `waitUntilTick` computed as `currentTick` itself for
  * Green (proceed at the next legitimate dispatch) or the headway-adjusted tick for Red — and lets
  * the existing `WaitingSignal` branch in `actSpontaneous` (unchanged, out of this handler's
  * scope) perform the actual `leavingLinkFn()` call once genuinely re-dispatched.
  */
class CarSignalHandlerSpec extends AnyFlatSpec with Matchers {

  private def newCarState(status: model.hybrid.entity.state.enumeration.MovableStatusEnum = WaitingSignalState): CarState = {
    val s = CarState(
      startTick = 0L,
      origin = "n_origin",
      destination = "n_dest",
      actorType = ActorTypeEnum.Car,
      size = 4.5
    )
    s.status = status
    s
  }

  private case class Fixture(
    handler: CarSignalHandler,
    finishCalls: mutable.ArrayBuffer[Option[Tick]],
    scheduleEventCalls: mutable.ArrayBuffer[Tick],
    leavingLinkCalls: mutable.ArrayBuffer[Unit],
    waitUntilTicks: mutable.ArrayBuffer[Option[Tick]],
    reported: mutable.ArrayBuffer[Map[String, Any]]
  )

  private def newFixture(currentTick: Tick = 100L): Fixture = {
    val finishCalls = mutable.ArrayBuffer.empty[Option[Tick]]
    val scheduleEventCalls = mutable.ArrayBuffer.empty[Tick]
    val leavingLinkCalls = mutable.ArrayBuffer.empty[Unit]
    val waitUntilTicks = mutable.ArrayBuffer.empty[Option[Tick]]
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]

    val journeyReporter = new CarJourneyReporter(
      reportFn = (data, _) => reported += data,
      entityIdFn = () => "htcaid:car;car_test",
      currentTickFn = () => currentTick,
      tripOriginFn = () => Some("n_origin"),
      tripDestFn = () => Some("n_dest"),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => DriverAttributes()
    )

    val handler = new CarSignalHandler(
      reportFn = (data, _) => reported += data,
      entityIdFn = () => "htcaid:car;car_test",
      currentTickFn = () => currentTick,
      journeyReporter = journeyReporter,
      onFinishSpontaneousFn = tick => finishCalls += tick,
      scheduleEventFn = tick => scheduleEventCalls += tick,
      leavingLinkFn = () => leavingLinkCalls += (()),
      selfDestructFn = () => (),
      isPersonCentricFn = () => false,
      logWarnFn = _ => (),
      logStaleEventDebugFn = _ => (),
      sendMessageFn = (_, _, _, _) => (),
      getCurrentNodeFn = () => "n_current",
      getNextLinkFn = () => "link_next",
      getTripDestinationFn = () => Some("n_dest"),
      setSignalWaitUntilTickFn = tick => waitUntilTicks += tick,
      onSignalWaitFn = _ => ()
    )

    Fixture(handler, finishCalls, scheduleEventCalls, leavingLinkCalls, waitUntilTicks, reported)
  }

  "handleSignalState" should "ignore a reply when the car is no longer WaitingSignalState (stale/duplicate guard)" in {
    val f = newFixture()
    val state = newCarState(status = Moving)

    f.handler.handleSignalState(SignalStateData(phase = Red, nextTick = 130L, queuePosition = 5), state)

    state.status shouldBe Moving
    f.finishCalls shouldBe empty
    f.scheduleEventCalls shouldBe empty
    f.leavingLinkCalls shouldBe empty
    f.waitUntilTicks shouldBe empty
  }

  it should "on Red, re-register via scheduleEvent (not onFinishSpontaneous) at data.nextTick + queuePosition * 2s headway" in {
    val f = newFixture(currentTick = 100L)
    val state = newCarState()

    f.handler.handleSignalState(SignalStateData(phase = Red, nextTick = 130L, queuePosition = 3), state)

    state.status shouldBe WaitingSignal
    f.waitUntilTicks shouldBe mutable.ArrayBuffer(Some(136L)) // 130 + 3*2
    f.scheduleEventCalls shouldBe mutable.ArrayBuffer(136L)
    f.finishCalls shouldBe empty
    f.leavingLinkCalls shouldBe empty
  }

  it should "on Red with queuePosition 0, wait exactly until data.nextTick (no headway padding)" in {
    val f = newFixture(currentTick = 100L)
    val state = newCarState()

    f.handler.handleSignalState(SignalStateData(phase = Red, nextTick = 130L, queuePosition = 0), state)

    f.scheduleEventCalls shouldBe mutable.ArrayBuffer(130L)
  }

  it should "on Green, re-register via scheduleEvent at the current tick instead of calling leavingLinkFn directly" in {
    // The car already fully deregistered (onFinishSpontaneous(None)) when it sent the request —
    // there's no pending spontaneous event here to resolve, and no runningEvents entry held open
    // waiting on this reply. handleSignalState's job is only to re-register; the actual
    // leavingLinkFn() call happens later, from a genuine actSpontaneous dispatch driven by the
    // existing WaitingSignal branch (out of this handler's scope, unchanged).
    val f = newFixture(currentTick = 100L)
    val state = newCarState()

    f.handler.handleSignalState(SignalStateData(phase = Green, nextTick = 100L, queuePosition = 0), state)

    state.status shouldBe WaitingSignal
    f.waitUntilTicks shouldBe mutable.ArrayBuffer(Some(100L))
    f.scheduleEventCalls shouldBe mutable.ArrayBuffer(100L)
    f.finishCalls shouldBe empty
    f.leavingLinkCalls shouldBe empty
  }

  it should "never call onFinishSpontaneous from handleSignalState at all, for either phase" in {
    // Regression guard for the deferFinishSpontaneous removal: calling onFinishSpontaneous here
    // would be resolving a spontaneous event this car never has pending at this point (it already
    // finished with None before the reply could arrive) -- exactly the "silent scheduling add,
    // no Global TM re-notification" trap scheduleEvent exists to avoid. See KNOWN_GAPS.md-style
    // reasoning in CarSignalHandler's comments for the full rationale.
    val f = newFixture(currentTick = 100L)

    f.handler.handleSignalState(SignalStateData(phase = Red, nextTick = 130L, queuePosition = 83), state = newCarState())
    f.handler.handleSignalState(SignalStateData(phase = Green, nextTick = 100L, queuePosition = 0), state = newCarState())

    f.finishCalls shouldBe empty
    f.scheduleEventCalls shouldBe mutable.ArrayBuffer(296L, 100L) // 130 + 83*2, then Green's currentTick
  }
}
