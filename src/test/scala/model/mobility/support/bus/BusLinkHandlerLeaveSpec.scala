package org.interscity.htc
package model.mobility.support.bus

import core.types.Tick
import model.mobility.entity.event.data.link.LinkInfoData
import model.mobility.entity.state.BusState
import model.mobility.entity.state.enumeration.MovableStatusEnum.Moving
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** A bus that leaves a plain MESO link without waiting for the Link's reply must end up exactly where the reply
  * handler would have left it.
  */
class BusLinkHandlerLeaveSpec extends AnyFlatSpec with Matchers {
  private val linkId     = 41L
  private val linkLength = 200.0
  private val leaveTick  = 120L

  private case class Outcome(reports: List[Map[String, Any]], finishes: List[Option[Tick]], journeys: List[(String, Long)],
                             destructs: Int, distance: Double, ackRequired: Boolean, exitTicks: List[Option[Tick]],
                             arrivalPos: Double)

  private def run(routeDepleted: Boolean, viaReply: Boolean): Outcome = {
    val reports   = mutable.ListBuffer.empty[Map[String, Any]]
    val finishes  = mutable.ListBuffer.empty[Option[Tick]]
    val journeys  = mutable.ListBuffer.empty[(String, Long)]
    val exitTicks = mutable.ListBuffer.empty[Option[Tick]]
    var destructs = 0
    val state = BusState(startTick = 0L, label = "bus-1", capacity = 40, busStops = Map.empty, numberOfPorts = 2,
      origin = 1L, destination = 2L, size = 12.0)
    state.status = Moving
    state.journey.mesoLinkLength = linkLength
    state.journey.leaveAckRequired = false
    if (!routeDepleted) state.bestRoute = Some(mutable.Queue((10L, 20L)))
    val reporter = new BusJourneyReporter(
      reportFn = (_, _) => (), entityIdFn = () => "bus", currentTickFn = () => leaveTick, journeyStateFn = () => state.journey)
    val handler = new BusLinkHandler(
      reportFn = (data, _) => { reports += data; () },
      entityIdFn = () => "bus",
      currentTickFn = () => leaveTick,
      journeyReporter = reporter,
      onFinishSpontaneousFn = next => { finishes += next; () },
      onFinishDestructFn = () => destructs += 1,
      finishJourneyFn = (reason, node) => { journeys += reason -> node; () },
      setMesoExitTickFn = t => { exitTicks += t; () },
      restoreRouteIfMissingFn = _ => ()
    )
    if (viaReply) handler.handleLeaveLink(linkId, LinkInfoData(linkLength = linkLength), state)
    else handler.leaveWithoutReply(linkId, state)
    Outcome(reports.toList, finishes.toList, journeys.toList, destructs, state.distance, state.journey.leaveAckRequired,
      exitTicks.toList, reporter.sumoArrivalPos)
  }

  "a bus leaving a plain MESO link without a reply" should "leave a continuing trip exactly as the reply handler does" in {
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
    withoutReply.destructs shouldBe 1
    withoutReply.finishes shouldBe empty
  }

  it should "forget that this link skipped the ack, so a later link starts from the safe default" in {
    run(routeDepleted = false, viaReply = false).ackRequired shouldBe true
  }
}
