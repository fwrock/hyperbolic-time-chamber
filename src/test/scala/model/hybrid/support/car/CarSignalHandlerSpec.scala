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

/** Regression coverage for docs/KNOWN_GAPS.md's "+161-167s signal-wait overshoot" finding.
  *
  * `requestSignalState`'s message-send branch (the one that used to call `onFinishSpontaneousFn`
  * immediately after sending `RequestSignalStateData`, which is what let `WaitingSignalState`'s
  * per-tick retry resend the request and inflate `NodeState.signalWaitingCounts`) reads
  * `CityMapUtil.nodesById` — a JVM-wide singleton with an eager `lazy val` that loads
  * `city_map.json` from disk and throws when it's absent, with no seam to fake it in this shared
  * test JVM (see `RaptorMultiModalEngineSpec`'s doc comment for the established precedent on why
  * that branch isn't unit-tested directly here). What *is* fully testable, and is where the actual
  * fix's behavior lives, is [[CarSignalHandler.handleSignalState]] — the reply-side half of the
  * consistency-critical exchange, which is now the *only* place that resolves the spontaneous
  * event for a car waiting on a signal.
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
    leavingLinkCalls: mutable.ArrayBuffer[Unit],
    waitUntilTicks: mutable.ArrayBuffer[Option[Tick]],
    reported: mutable.ArrayBuffer[Map[String, Any]]
  )

  private def newFixture(currentTick: Tick = 100L): Fixture = {
    val finishCalls = mutable.ArrayBuffer.empty[Option[Tick]]
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

    Fixture(handler, finishCalls, leavingLinkCalls, waitUntilTicks, reported)
  }

  "handleSignalState" should "ignore a reply when the car is no longer WaitingSignalState (stale/duplicate guard)" in {
    val f = newFixture()
    val state = newCarState(status = Moving)

    f.handler.handleSignalState(SignalStateData(phase = Red, nextTick = 130L, queuePosition = 5), state)

    state.status shouldBe Moving
    f.finishCalls shouldBe empty
    f.leavingLinkCalls shouldBe empty
    f.waitUntilTicks shouldBe empty
  }

  it should "on Red, resolve the spontaneous event exactly once, at data.nextTick + queuePosition * 2s headway" in {
    val f = newFixture(currentTick = 100L)
    val state = newCarState()

    f.handler.handleSignalState(SignalStateData(phase = Red, nextTick = 130L, queuePosition = 3), state)

    state.status shouldBe WaitingSignal
    f.waitUntilTicks shouldBe mutable.ArrayBuffer(Some(136L)) // 130 + 3*2
    f.finishCalls shouldBe mutable.ArrayBuffer(Some(136L))
    f.leavingLinkCalls shouldBe empty
  }

  it should "on Red with queuePosition 0, wait exactly until data.nextTick (no headway padding)" in {
    val f = newFixture(currentTick = 100L)
    val state = newCarState()

    f.handler.handleSignalState(SignalStateData(phase = Red, nextTick = 130L, queuePosition = 0), state)

    f.finishCalls shouldBe mutable.ArrayBuffer(Some(130L))
  }

  it should "on Green, leave the link and not call onFinishSpontaneous directly (leavingLinkFn owns resolution)" in {
    val f = newFixture()
    val state = newCarState()

    f.handler.handleSignalState(SignalStateData(phase = Green, nextTick = 100L, queuePosition = 0), state)

    f.leavingLinkCalls should have size 1
    f.finishCalls shouldBe empty
  }

  it should "never call onFinishSpontaneous more than once for a single Red reply, even with a large inflated queuePosition" in {
    // Regression guard: before the fix, a retry storm could hand handleSignalState a
    // queuePosition inflated by dozens of phantom self-resends (see NodeEventHandlerSpec's
    // "no per-request decrement" test for where that inflation came from). This test doesn't
    // recreate the retry storm itself (that no longer exists in the code), it just locks in
    // that handleSignalState resolves the spontaneous event exactly once regardless of how
    // large queuePosition is, so if a future change reintroduces multiple resolutions per
    // reply, it fails here rather than surfacing as a multi-minute travel-time anomaly again.
    val f = newFixture(currentTick = 100L)
    val state = newCarState()

    f.handler.handleSignalState(SignalStateData(phase = Red, nextTick = 130L, queuePosition = 83), state)

    f.finishCalls should have size 1
    f.finishCalls.head shouldBe Some(296L) // 130 + 83*2
  }
}
