package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.state.BusState
import model.hybrid.entity.state.enumeration.MovableStatusEnum.Moving

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for the Time Warp `AwaitedReply` wiring ported into `BusLinkHandler`.
  * Unlike `CarLinkHandler`/`BicycleLinkHandler`, `handleLeaveLink` had NO staleness guard at all in
  * conservative mode before this fix -- so conservative-mode behavior (always process) is preserved
  * unchanged here; the new guard only activates under Time Warp, where `Movable.leavingLink`
  * already registers an awaited reply (`Movable.leaveLinkAwaitKey`) that a rollback could otherwise
  * silently orphan (see `BicycleSignalHandlerSpec`'s doc for the shared rationale).
  */
class BusLinkHandlerSpec extends AnyFlatSpec with Matchers {

  private def newBusState(status: model.hybrid.entity.state.enumeration.MovableStatusEnum = Moving): BusState = {
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
    // Non-empty so `routeDepleted` is false in handleLeaveLink -- exercises the
    // onFinishSpontaneousFn(Some(tick+1)) branch (recorded by this spec's fixture) rather than the
    // finishJourneyFn/onFinishDestructFn branch (not recorded, irrelevant to this guard's coverage).
    s.bestRoute = Some(mutable.Queue((10L, 20L)))
    s
  }

  private case class Fixture(
    handler: BusLinkHandler,
    finishCalls: mutable.ArrayBuffer[Option[Tick]],
    outstanding: mutable.Map[String, String],
    consumed: mutable.ArrayBuffer[String]
  )

  private def newFixture(currentTick: Tick = 100L, isTimeWarp: Boolean = true): Fixture = {
    val finishCalls = mutable.ArrayBuffer.empty[Option[Tick]]
    val outstanding = mutable.Map.empty[String, String]
    val consumed = mutable.ArrayBuffer.empty[String]

    val journey = org.interscity.htc.model.hybrid.entity.state.BusJourneyState()
    val journeyReporter = new BusJourneyReporter(
      reportFn = (_, _) => (),
      entityIdFn = () => "htcaid:bus;bus_test",
      currentTickFn = () => currentTick,
      journeyStateFn = () => journey
    )

    val handler = new BusLinkHandler(
      reportFn = (_, _) => (),
      entityIdFn = () => "htcaid:bus;bus_test",
      currentTickFn = () => currentTick,
      journeyReporter = journeyReporter,
      onFinishSpontaneousFn = tick => finishCalls += tick,
      onFinishDestructFn = () => (),
      finishJourneyFn = (_, _) => (),
      setMesoExitTickFn = _ => (),
      restoreRouteIfMissingFn = _ => (),
      logDebugFn = _ => (),
      isTimeWarpFn = () => isTimeWarp,
      isAwaitingReplyFn = (key, kind, _) => outstanding.get(key).contains(kind),
      consumeAwaitedReplyFn = key => { consumed += key; outstanding.remove(key) }
    )

    Fixture(handler, finishCalls, outstanding, consumed)
  }

  "handleLeaveLink under Time Warp" should "accept a genuine reply that is registered as outstanding" in {
    val f = newFixture()
    val linkId = 55L
    f.outstanding.put(model.hybrid.actor.Movable.leaveLinkAwaitKey(linkId), "LeaveLinkData")
    val state = newBusState()

    f.handler.handleLeaveLink(linkId, LinkInfoData(linkLength = 100.0), state)

    state.distance shouldBe 100.0
    f.finishCalls should not be empty
    f.consumed shouldBe mutable.ArrayBuffer(model.hybrid.actor.Movable.leaveLinkAwaitKey(linkId))
  }

  it should "drop a genuinely stale reply when nothing is outstanding for that link" in {
    val f = newFixture()
    val linkId = 55L
    val state = newBusState()

    f.handler.handleLeaveLink(linkId, LinkInfoData(linkLength = 100.0), state)

    state.distance shouldBe 0.0 // untouched -- never accepted
    f.finishCalls shouldBe empty
    f.consumed shouldBe empty
  }

  "handleLeaveLink under conservative mode" should "keep processing unconditionally (pre-existing behavior, unchanged)" in {
    val f = newFixture(isTimeWarp = false)
    val linkId = 55L
    val state = newBusState()

    f.handler.handleLeaveLink(linkId, LinkInfoData(linkLength = 100.0), state)

    state.distance shouldBe 100.0
    f.finishCalls should not be empty
  }
}
