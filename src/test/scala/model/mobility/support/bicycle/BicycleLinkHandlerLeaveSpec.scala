package org.interscity.htc
package model.mobility.support.bicycle

import core.types.Tick
import model.mobility.entity.event.data.link.LinkInfoData
import model.mobility.entity.state.BicycleState
import model.mobility.entity.state.DriverAttributes
import model.mobility.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** A bicycle that leaves a plain MESO link without waiting for the Link's reply must end up exactly where the reply
  * handler would have left it.
  */
class BicycleLinkHandlerLeaveSpec extends AnyFlatSpec with Matchers {
  private val linkId     = 41L
  private val linkLength = 200.0
  private val leaveTick  = 120L

  private case class Outcome(reports: List[Map[String, Any]], finishes: List[Option[Tick]], journeys: List[(String, Long)],
                             privateFinishes: List[Long], selfDestructs: Int, distance: Double, ackRequired: Boolean,
                             exitTicks: List[Option[Tick]], arrivalPos: Double)

  private def run(routeDepleted: Boolean, viaReply: Boolean): Outcome = {
    val reports   = mutable.ListBuffer.empty[Map[String, Any]]
    val finishes  = mutable.ListBuffer.empty[Option[Tick]]
    val journeys  = mutable.ListBuffer.empty[(String, Long)]
    val privates  = mutable.ListBuffer.empty[Long]
    val exitTicks = mutable.ListBuffer.empty[Option[Tick]]
    var destructs = 0
    val state = BicycleState(startTick = 0L, origin = 1L, destination = 2L, actorType = ActorTypeEnum.Bicycle, size = 1.5)
    state.status = MovableStatusEnum.Moving
    state.mesoLinkLength = linkLength
    state.leaveAckRequired = false
    if (!routeDepleted) state.bestRoute = Some(mutable.Queue((10L, 20L)))
    val reporter = new BicycleJourneyReporter(
      reportFn = (_, _) => (), entityIdFn = () => "v", currentTickFn = () => leaveTick, tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L), tripStartTickFn = () => Some(0L), driverAttrsFn = () => DriverAttributes())
    val handler = new BicycleLinkHandler(
      reportFn = (data, _) => { reports += data; () },
      entityIdFn = () => "v",
      currentTickFn = () => leaveTick,
      journeyReporter = reporter,
      onFinishSpontaneousFn = next => { finishes += next; () },
      onFinishPrivateVehicleFn = node => { privates += node; () },
      selfDestructFn = () => destructs += 1,
      isPersonCentricFn = () => false,
      finishJourneyFn = (reason, node) => { journeys += reason -> node; () },
      setMesoExitTickFn = t => { exitTicks += t; () },
      getTripDestinationFn = () => Some(2L),
      logDebugFn = _ => ()
    )
    if (viaReply) handler.handleLeaveLink(linkId, LinkInfoData(linkLength = linkLength), state)
    else handler.leaveWithoutReply(linkId, state)
    Outcome(reports.toList, finishes.toList, journeys.toList, privates.toList, destructs, state.distance,
      state.leaveAckRequired, exitTicks.toList, reporter.sumoArrivalPos)
  }

  "a bicycle leaving a plain MESO link without a reply" should "leave a continuing trip exactly as the reply handler does" in {
    val withoutReply = run(routeDepleted = false, viaReply = false)
    // The reply handler leaves the flag at its default; the stored length is what replaces the reply's.
    withoutReply.copy(ackRequired = true) shouldBe run(routeDepleted = false, viaReply = true).copy(ackRequired = true)
    val expectedNext =
      if (model.mobility.util.VehicleSimulationConfig.mesoImmediateNodeTransfer) leaveTick else leaveTick + 1
    withoutReply.finishes shouldBe List(Some(expectedNext))
    withoutReply.distance shouldBe linkLength
    withoutReply.arrivalPos shouldBe linkLength
  }

  it should "finish a trip whose route is depleted exactly as the reply handler does" in {
    val withoutReply = run(routeDepleted = true, viaReply = false)
    withoutReply shouldBe run(routeDepleted = true, viaReply = true)
    withoutReply.journeys shouldBe List("reached_destination" -> 2L)
    withoutReply.privateFinishes shouldBe List(2L)
    withoutReply.selfDestructs shouldBe 1
  }

  it should "forget that this link skipped the ack, so a later link starts from the safe default" in {
    run(routeDepleted = false, viaReply = false).ackRequired shouldBe true
  }
}
