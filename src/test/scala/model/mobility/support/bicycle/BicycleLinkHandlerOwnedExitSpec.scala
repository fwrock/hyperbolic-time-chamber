package org.interscity.htc
package model.mobility.support.bicycle

import core.types.Tick
import model.mobility.entity.event.data.link.LinkInfoData
import model.mobility.entity.state.BicycleState
import model.mobility.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import model.mobility.entity.state.DriverAttributes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** When the Link owns a bicycle's exit, the entry reply arrives at the exit tick but describes the entry. */
class BicycleLinkHandlerOwnedExitSpec extends AnyFlatSpec with Matchers {
  private val linkId = 41L

  private class Fixture(currentTick: Tick) {
    val reports   = mutable.ListBuffer.empty[Map[String, Any]]
    val finishes  = mutable.ListBuffer.empty[Option[Tick]]
    val exitTicks = mutable.ListBuffer.empty[Option[Tick]]
    val state = BicycleState(startTick = 0L, origin = 1L, destination = 2L, actorType = ActorTypeEnum.Bicycle, size = 1.5)
    state.status = MovableStatusEnum.Moving
    state.bestRoute = Some(mutable.Queue((10L, 20L)))
    val reporter = new BicycleJourneyReporter(
      reportFn = (_, _) => (), entityIdFn = () => "v", currentTickFn = () => currentTick, tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L), tripStartTickFn = () => Some(0L), driverAttrsFn = () => DriverAttributes())
    val handler = new BicycleLinkHandler(
      reportFn = (data, _) => { reports += data; () },
      entityIdFn = () => "v",
      currentTickFn = () => currentTick,
      journeyReporter = reporter,
      onFinishSpontaneousFn = next => { finishes += next; () },
      onFinishPrivateVehicleFn = _ => (),
      selfDestructFn = () => (),
      isPersonCentricFn = () => false,
      finishJourneyFn = (_, _) => (),
      setMesoExitTickFn = t => { exitTicks += t; () },
      getTripDestinationFn = () => Some(2L),
      logDebugFn = _ => ()
    )
    val info = LinkInfoData(linkLength = 200.0, linkCapacity = 600.0, linkNumberOfCars = 1, linkFreeSpeed = 40.0, linkLanes = 1)
  }

  "an entry reply the Link held until the exit tick" should "report the entry at the entry tick and leave the wake to the caller" in {
    val f = new Fixture(currentTick = 1000L)
    f.handler.handleEnterLink(linkId, f.info.copy(entryTick = 100L), f.state)

    f.reports.map(r => r("tick")) shouldBe List(100L)
    f.exitTicks.flatten.headOption.exists(_ <= 1000L) shouldBe true
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

  "a bicycle leaving a link the Link owns" should "always continue at the next tick, and forget that it did" in {
    val f = new Fixture(currentTick = 120L)
    f.state.mesoLinkLength = 200.0
    f.state.linkOwnedExit = true

    f.handler.leaveWithoutReply(linkId, f.state)

    f.finishes.toList shouldBe List(Some(121L))
    f.state.linkOwnedExit shouldBe false
  }
}
