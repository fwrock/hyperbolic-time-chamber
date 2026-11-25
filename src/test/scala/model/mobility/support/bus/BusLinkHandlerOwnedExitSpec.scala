package org.interscity.htc
package model.mobility.support.bus

import core.types.Tick
import model.mobility.entity.event.data.link.LinkInfoData
import model.mobility.entity.state.BusState
import model.mobility.entity.state.enumeration.MovableStatusEnum.Moving
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** When the Link owns a bus's exit, the entry reply arrives at the exit tick but describes the entry. */
class BusLinkHandlerOwnedExitSpec extends AnyFlatSpec with Matchers {
  private val linkId = 41L

  private class Fixture(currentTick: Tick) {
    val reports   = mutable.ListBuffer.empty[Map[String, Any]]
    val finishes  = mutable.ListBuffer.empty[Option[Tick]]
    val exitTicks = mutable.ListBuffer.empty[Option[Tick]]
    val state = BusState(startTick = 0L, label = "bus-1", capacity = 40, busStops = Map.empty, numberOfPorts = 2,
      origin = 1L, destination = 2L, size = 12.0)
    state.status = Moving
    state.bestRoute = Some(mutable.Queue((10L, 20L)))
    val reporter = new BusJourneyReporter(
      reportFn = (_, _) => (), entityIdFn = () => "bus", currentTickFn = () => currentTick, journeyStateFn = () => state.journey)
    val handler = new BusLinkHandler(
      reportFn = (data, _) => { reports += data; () },
      entityIdFn = () => "bus",
      currentTickFn = () => currentTick,
      journeyReporter = reporter,
      onFinishSpontaneousFn = next => { finishes += next; () },
      onFinishDestructFn = () => (),
      finishJourneyFn = (_, _) => (),
      setMesoExitTickFn = t => { exitTicks += t; () },
      restoreRouteIfMissingFn = _ => ()
    )
    val info = LinkInfoData(linkLength = 200.0, linkCapacity = 600.0, linkNumberOfCars = 1, linkFreeSpeed = 40.0, linkLanes = 1)
  }

  "an entry reply the Link held until the exit tick" should "report the entry at the entry tick and leave the wake to the caller" in {
    val f = new Fixture(currentTick = 119L)
    f.handler.handleEnterLink(linkId, f.info.copy(entryTick = 100L), f.state)

    f.reports.map(r => r("tick")) shouldBe List(100L)
    f.exitTicks.flatten.headOption.exists(_ <= 119L) shouldBe true
    f.finishes shouldBe empty
  }

  it should "still schedule the exit itself when the vehicle computes a later one than the tick it is answered at" in {
    val f = new Fixture(currentTick = 105L)
    f.handler.handleEnterLink(linkId, f.info.copy(entryTick = 100L), f.state)

    f.finishes.flatten.headOption.exists(_ > 105L) shouldBe true
  }

  "an ordinary entry reply" should "report the entry at the tick it arrives and schedule the exit" in {
    val f = new Fixture(currentTick = 100L)
    f.handler.handleEnterLink(linkId, f.info, f.state)

    f.reports.map(r => r("tick")) shouldBe List(100L)
    f.finishes.flatten.headOption.exists(_ > 100L) shouldBe true
  }

  "a bus leaving a link the Link owns" should "always continue at the next tick, and forget that it did" in {
    val f = new Fixture(currentTick = 120L)
    f.state.journey.mesoLinkLength = 200.0
    f.state.journey.linkOwnedExit = true

    f.handler.leaveWithoutReply(linkId, f.state)

    f.finishes.toList shouldBe List(Some(121L))
    f.state.journey.linkOwnedExit shouldBe false
  }
}
