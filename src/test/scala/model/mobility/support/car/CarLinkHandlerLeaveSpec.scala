package org.interscity.htc
package model.mobility.support.car

import core.types.Tick
import model.mobility.entity.event.data.link.LinkInfoData
import model.mobility.entity.state.enumeration.ActorTypeEnum
import model.mobility.entity.state.enumeration.MovableStatusEnum.Moving
import model.mobility.entity.state.{ CarJourneyState, CarState, DriverAttributes }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** A Car that leaves a plain MESO link without waiting for the Link's reply must end up exactly where the reply
  * handler would have left it.
  */
class CarLinkHandlerLeaveSpec extends AnyFlatSpec with Matchers {
  private val linkId     = 41L
  private val linkLength = 200.0
  private val entryTick  = 90L
  private val leaveTick  = 120L

  private case class Outcome(reports: List[Map[String, Any]], finishes: List[Option[Tick]], cleanups: List[(String, Long)],
                             distance: Double, ackRequired: Boolean, linkId: Option[Long], linkEntry: Option[Tick])

  private def run(routeDepleted: Boolean, viaReply: Boolean): Outcome = {
    val reports   = mutable.ListBuffer.empty[Map[String, Any]]
    val finishes  = mutable.ListBuffer.empty[Option[Tick]]
    val cleanups  = mutable.ListBuffer.empty[(String, Long)]
    var currentLink: Option[Long] = Some(linkId)
    var linkEntry: Option[Tick]   = Some(entryTick)
    val state = CarState(startTick = 0L, origin = 1L, destination = 2L, actorType = ActorTypeEnum.Car, size = 4.5)
    state.status = Moving
    state.journey.leaveAckRequired = false
    if (!routeDepleted) state.movableBestRoute = Some(mutable.Queue((5L, 6L)))
    val journeyState = state.journey
    val reporter = new CarJourneyReporter(
      reportFn = (_, _) => (), entityIdFn = () => "car", currentTickFn = () => leaveTick, tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L), tripStartTickFn = () => Some(0L), driverAttrsFn = () => DriverAttributes(),
      journeyStateFn = () => journeyState)
    val handler = new CarLinkHandler(
      reportFn = (data, _) => { reports += data; () },
      entityIdFn = () => "car",
      currentTickFn = () => leaveTick,
      journeyReporter = reporter,
      onFinishSpontaneousFn = next => { finishes += next; () },
      finishAndCleanupFn = (reason, node) => { cleanups += reason -> node; () },
      logStaleEventDebugFn = _ => (),
      setCurrentLinkIdFn = v => currentLink = v,
      setCurrentLinkLengthFn = _ => (),
      setLinkEntryTickFn = v => linkEntry = v,
      getLinkEntryTickFn = () => linkEntry,
      setMesoExitTickFn = _ => (),
      getTripDestinationFn = () => Some(2L)
    )
    if (viaReply) handler.handleLeaveLink(linkId, LinkInfoData(linkLength = linkLength), state)
    else handler.leaveWithoutReply(linkId, linkLength, state)
    Outcome(reports.toList, finishes.toList, cleanups.toList, state.distance, state.journey.leaveAckRequired, currentLink, linkEntry)
  }

  "leaving a plain MESO link without a reply" should "leave a continuing trip exactly as the reply handler does" in {
    val withoutReply = run(routeDepleted = false, viaReply = false)
    withoutReply shouldBe run(routeDepleted = false, viaReply = true)
    val expectedNext =
      if (model.mobility.util.VehicleSimulationConfig.mesoImmediateNodeTransfer) leaveTick else leaveTick + 1
    withoutReply.finishes shouldBe List(Some(expectedNext))
    withoutReply.distance shouldBe linkLength
  }

  it should "finish a trip whose route is depleted exactly as the reply handler does" in {
    val withoutReply = run(routeDepleted = true, viaReply = false)
    withoutReply shouldBe run(routeDepleted = true, viaReply = true)
    withoutReply.cleanups shouldBe List("reached_destination" -> 2L)
    withoutReply.finishes shouldBe empty
  }

  it should "forget that this link skipped the ack, so a later link starts from the safe default" in {
    run(routeDepleted = false, viaReply = false).ackRequired shouldBe true
  }
}
