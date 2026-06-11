package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.state.CarState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{Finished, Moving, Parked}
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed

/** Handles MESO link enter/leave events for Car actors.
  *
  * Responsibilities:
  *   - actHandleReceiveEnterLinkInfo: speed calculation, SUMO depart tracking
  *   - actHandleReceiveLeaveLinkInfo: distance accumulation, halting state, route completion
  */
class CarLinkHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: CarJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val finishAndCleanupFn: (String, String) => Unit,
  private val logStaleEventDebugFn: String => Unit,
  private val setCurrentLinkIdFn: Option[String] => Unit,
  private val setCurrentLinkLengthFn: Double => Unit,
  private val setLinkEntryTickFn: Option[Tick] => Unit,
  private val getLinkEntryTickFn: () => Option[Tick],
  private val setMesoExitTickFn: Option[Tick] => Unit,
  private val getTripDestinationFn: () => Option[String]
) {

  def handleEnterLink(linkId: String, data: LinkInfoData, state: CarState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    setCurrentLinkIdFn(Some(linkId))
    setCurrentLinkLengthFn(data.linkLength)
    setLinkEntryTickFn(Some(tick))

    val speed = linkDensitySpeed(
      length       = data.linkLength,
      capacity     = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed    = data.linkFreeSpeed,
      lanes        = data.linkLanes
    )

    val time = data.linkLength / speed
    state.status = Moving
    journeyReporter.sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, data.linkFreeSpeed)

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(tick)
      journeyReporter.sumoDepartSpeed = speed
      journeyReporter.sumoDepartLane  = Some(s"${linkId}_0")
      journeyReporter.sumoDepartPos   = 0.0
    }

    reportFn(
      Map(
        "event_type"      -> "enter_link",
        "car_id"          -> entityId,
        "link_id"         -> linkId,
        "mode"            -> "MESO",
        "link_length"     -> data.linkLength,
        "link_capacity"   -> data.linkCapacity,
        "cars_in_link"    -> data.linkNumberOfCars,
        "free_speed"      -> data.linkFreeSpeed,
        "calculated_speed" -> speed,
        "travel_time"     -> time,
        "lanes"           -> data.linkLanes,
        "tick"            -> tick
      ),
      "enter_link"
    )

    val exitTick = tick + Math.ceil(time).toLong
    setMesoExitTickFn(Some(exitTick))
    onFinishSpontaneousFn(Some(exitTick))
  }

  def handleLeaveLink(linkId: String, data: LinkInfoData, state: CarState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    // Guard: discard stale callbacks for already-finalised trips
    if (state.status == Parked || state.status == Finished) {
      logStaleEventDebugFn(
        s"$entityId: Discarding stale ReceiveLeaveLinkInfo for link $linkId " +
          s"(status=${state.status}, trip already finalized)."
      )
      return
    }

    state.distance += data.linkLength
    journeyReporter.sumoArrivalSpeed = 0.0
    journeyReporter.sumoArrivalLane  = Some(s"${linkId}_0")
    journeyReporter.sumoArrivalPos   = data.linkLength

    getLinkEntryTickFn().foreach { entryTick =>
      val travelTimeSecs = (tick - entryTick).toDouble // 1 tick = 1 second
      val actualSpeed    = if (travelTimeSecs > 0) data.linkLength / travelTimeSecs else 0.0
      journeyReporter.updateHaltingState(actualSpeed, travelTimeSecs)
    }

    setCurrentLinkIdFn(None)
    setCurrentLinkLengthFn(0.0)
    setLinkEntryTickFn(None)
    setMesoExitTickFn(None)

    reportFn(
      Map(
        "event_type"     -> "leave_link",
        "car_id"         -> entityId,
        "link_id"        -> linkId,
        "mode"           -> "MESO",
        "link_length"    -> data.linkLength,
        "total_distance" -> state.distance,
        "tick"           -> tick
      ),
      "leave_link"
    )

    val routeDepleted = state.currentPath.isEmpty && state.bestRoute.forall(_.isEmpty)
    if (routeDepleted && state.status != Finished) {
      val tripDest = getTripDestinationFn().getOrElse(state.destination)
      finishAndCleanupFn("reached_destination", tripDest)
    } else {
      onFinishSpontaneousFn(Some(tick + 1))
    }
  }
}
