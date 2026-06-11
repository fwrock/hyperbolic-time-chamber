package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.state.BusState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{Finished, Moving}
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed

/** Handles MESO link enter/leave events for Bus actors.
  *
  * Responsibilities:
  *   - handleEnterLink: speed calculation, SUMO depart tracking
  *   - handleLeaveLink: distance accumulation, route completion check
  */
class BusLinkHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: BusJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val onFinishDestructFn: () => Unit,
  private val finishJourneyFn: (String, String) => Unit,
  private val setMesoExitTickFn: Option[Tick] => Unit,
  private val restoreRouteIfMissingFn: String => Unit
) {

  def handleEnterLink(linkId: String, data: LinkInfoData, state: BusState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    val speed = linkDensitySpeed(
      length       = data.linkLength,
      capacity     = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed    = data.linkFreeSpeed,
      lanes        = data.linkLanes
    )
    val time = if (speed > 0.0) data.linkLength / speed else data.linkLength

    state.status = Moving
    journeyReporter.sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, data.linkFreeSpeed)
    journeyReporter.updateHaltingState(speed, 0.0)

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(tick)
      journeyReporter.sumoDepartSpeed = speed
      journeyReporter.sumoDepartLane  = Some(s"${linkId}_0")
      journeyReporter.sumoDepartPos   = 0.0
    }

    reportFn(
      Map(
        "event_type"  -> "enter_link",
        "bus_id"      -> entityId,
        "link_id"     -> linkId,
        "mode"        -> "MESO",
        "passengers"  -> state.people.size,
        "capacity"    -> state.capacity,
        "occupancy"   -> state.occupancyPercentage,
        "travel_time" -> time,
        "tick"        -> tick
      ),
      "enter_link"
    )

    val exitTick = tick + time.toLong
    setMesoExitTickFn(Some(exitTick))
    onFinishSpontaneousFn(Some(exitTick))
  }

  def handleLeaveLink(linkId: String, data: LinkInfoData, state: BusState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    state.distance += data.linkLength
    journeyReporter.sumoArrivalSpeed = 0.0
    journeyReporter.sumoArrivalLane  = Some(s"${linkId}_0")
    journeyReporter.sumoArrivalPos   = data.linkLength
    journeyReporter.updateHaltingState(0.0, 0.0)
    setMesoExitTickFn(None)

    reportFn(
      Map(
        "event_type"     -> "leave_link",
        "bus_id"         -> entityId,
        "link_id"        -> linkId,
        "mode"           -> "MESO",
        "passengers"     -> state.people.size,
        "total_distance" -> state.distance,
        "tick"           -> tick
      ),
      "leave_link"
    )

    restoreRouteIfMissingFn("ReceiveLeaveLinkInfo")
    val routeDepleted = state.currentPath.isEmpty && state.bestRoute.forall(_.isEmpty)
    if (routeDepleted && state.status != Finished) {
      finishJourneyFn("reached_destination", state.destination)
      onFinishDestructFn()
    } else {
      onFinishSpontaneousFn(Some(tick + 1))
    }
  }
}
