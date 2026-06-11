package org.interscity.htc
package model.hybrid.support.motorcycle

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.state.MotorcycleState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Finished, Moving, Parked }
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed

class MotorcycleLinkHandler(
  reportFn:                (Map[String, Any], String) => Unit,
  entityIdFn:              () => String,
  currentTickFn:           () => Tick,
  journeyReporter:         MotorcycleJourneyReporter,
  onFinishSpontaneousFn:   Option[Tick] => Unit,
  onFinishPrivateVehicleFn: String => Unit,
  selfDestructFn:          () => Unit,
  isPersonCentricFn:       () => Boolean,
  finishJourneyFn:         (String, String) => Unit,
  setMesoExitTickFn:       Option[Tick] => Unit,
  getTripDestinationFn:    () => Option[String],
  logDebugFn:              String => Unit
) {

  def handleEnterLink(linkId: String, data: LinkInfoData, state: MotorcycleState): Unit = {
    val baseSpeed       = linkDensitySpeed(
      length        = data.linkLength,
      capacity      = data.linkCapacity,
      numberOfCars  = data.linkNumberOfCars,
      freeSpeed     = data.linkFreeSpeed,
      lanes         = data.linkLanes
    )
    val motorcycleSpeed = baseSpeed * 1.2
    val time            = data.linkLength / motorcycleSpeed

    state.status = Moving
    journeyReporter.sumoIdealTravelTimeSeconds +=
      data.linkLength / math.max(0.1, data.linkFreeSpeed)
    journeyReporter.updateHaltingState(motorcycleSpeed, 0.0)
    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(currentTickFn())
      journeyReporter.sumoDepartSpeed = motorcycleSpeed
      journeyReporter.sumoDepartLane  = Some(s"${linkId}_0")
      journeyReporter.sumoDepartPos   = 0.0
    }
    reportFn(
      Map(
        "event_type"      -> "enter_link",
        "motorcycle_id"   -> entityIdFn(),
        "link_id"         -> linkId,
        "mode"            -> "MESO",
        "link_length"     -> data.linkLength,
        "travel_time"     -> time,
        "speed"           -> motorcycleSpeed,
        "speed_multiplier" -> 1.2,
        "tick"            -> currentTickFn()
      ),
      "enter_link"
    )
    val exitTick = currentTickFn() + Math.ceil(time).toLong
    setMesoExitTickFn(Some(exitTick))
    onFinishSpontaneousFn(Some(exitTick))
  }

  def handleLeaveLink(linkId: String, data: LinkInfoData, state: MotorcycleState): Unit = {
    if (state.status == Parked || state.status == Finished) {
      logDebugFn(
        s"${entityIdFn()}: Discarding stale ReceiveLeaveLinkInfo for link $linkId " +
          s"(status=${state.status}, trip already finalized)."
      )
      return
    }
    state.distance += data.linkLength
    journeyReporter.sumoArrivalSpeed = 0.0
    journeyReporter.sumoArrivalLane  = Some(s"${linkId}_0")
    journeyReporter.sumoArrivalPos   = data.linkLength
    journeyReporter.updateHaltingState(0.0, 0.0)
    setMesoExitTickFn(None)
    reportFn(
      Map(
        "event_type"     -> "leave_link",
        "motorcycle_id"  -> entityIdFn(),
        "link_id"        -> linkId,
        "mode"           -> "MESO",
        "total_distance" -> state.distance,
        "tick"           -> currentTickFn()
      ),
      "leave_link"
    )
    val routeDepleted =
      state.currentPath.isEmpty && state.bestRoute.forall(_.isEmpty)
    if (routeDepleted && state.status != Finished) {
      val tripDest = getTripDestinationFn().getOrElse(state.destination)
      finishJourneyFn("reached_destination", tripDest)
      onFinishPrivateVehicleFn(tripDest)
      onFinishSpontaneousFn(None)
      if (!isPersonCentricFn()) selfDestructFn()
    } else {
      onFinishSpontaneousFn(Some(currentTickFn() + 1))
    }
  }
}
