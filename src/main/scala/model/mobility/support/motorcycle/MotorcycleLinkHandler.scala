package org.interscity.htc
package model.mobility.support.motorcycle

import core.types.Tick
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.state.MotorcycleState
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ Finished, Moving, Parked }
import org.interscity.htc.model.mobility.util.SpeedUtil.linkEntryDensitySpeed

class MotorcycleLinkHandler(
  reportFn:                (Map[String, Any], String) => Unit,
  entityIdFn:              () => String,
  currentTickFn:           () => Tick,
  journeyReporter:         MotorcycleJourneyReporter,
  onFinishSpontaneousFn:   Option[Tick] => Unit,
  onFinishPrivateVehicleFn: Long => Unit,
  selfDestructFn:          () => Unit,
  isPersonCentricFn:       () => Boolean,
  finishJourneyFn: (String, Long) => Unit,
  setMesoExitTickFn:       Option[Tick] => Unit,
  getTripDestinationFn: () => Option[Long],
  logDebugFn:              String => Unit,
  // Defaulted to conservative-mode-safe no-ops so existing/other in-flight callers that predate
  // this Time Warp wiring keep compiling unchanged -- same pattern as CarSignalHandler/CarLinkHandler.
  isTimeWarpFn: () => Boolean = () => false,
  isAwaitingReplyFn: (String, String, Long) => Boolean = (_, _, _) => false,
  consumeAwaitedReplyFn: String => Unit = _ => ()
) {

  private def enterLinkAwaitKey(linkId: Long): String = model.mobility.actor.Movable.enterLinkAwaitKey(linkId)
  private def leaveLinkAwaitKey(linkId: Long): String = model.mobility.actor.Movable.leaveLinkAwaitKey(linkId)

  def handleEnterLink(linkId: Long, data: LinkInfoData, state: MotorcycleState): Unit = {
    val tick = if (data.entryTick >= 0L) data.entryTick else currentTickFn()
    consumeAwaitedReplyFn(enterLinkAwaitKey(linkId))
    state.mesoLinkLength = data.linkLength
    state.leaveAckRequired = data.leaveAckRequired
    val baseSpeed       = linkEntryDensitySpeed(
      length        = data.linkLength,
      capacity      = data.linkCapacity,
      numberOfCars  = data.linkNumberOfCars,
      freeSpeed     = data.linkFreeSpeed,
      lanes         = data.linkLanes
    )
    val motorcycleSpeed = baseSpeed * 1.2
    val motorcycleSpeedMs = motorcycleSpeed / 3.6
    val time            = data.linkLength / motorcycleSpeedMs

    state.status = Moving
    journeyReporter.sumoIdealTravelTimeSeconds +=
      data.linkLength / (math.max(0.1, data.linkFreeSpeed) / 3.6)
    journeyReporter.updateHaltingState(motorcycleSpeed, 0.0)
    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(tick)
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
        "tick"            -> tick
      ),
      "enter_link"
    )
    val exitTick = model.mobility.support.car.MesoLinkTiming.exitTick(tick, time, originAtEnd = false, carry = model.mobility.util.VehicleSimulationConfig.mesoCarryRounding, state)
    setMesoExitTickFn(Some(exitTick))
    // A reply the Link held until the exit tick: the caller continues the wake in this very callback.
    if (!(data.entryTick >= 0L && exitTick <= currentTickFn())) onFinishSpontaneousFn(Some(exitTick))
  }

  def handleLeaveLink(linkId: Long, data: LinkInfoData, state: MotorcycleState): Unit = {
    val awaitKey = leaveLinkAwaitKey(linkId)
    val isStale =
      if (isTimeWarpFn())
        !isAwaitingReplyFn(awaitKey, "LeaveLinkData", data.requestSeq)
      else
        state.status == Parked || state.status == Finished
    if (isStale) {
      logDebugFn(
        s"${entityIdFn()}: Discarding stale ReceiveLeaveLinkInfo for link $linkId " +
          s"(status=${state.status}, trip already finalized)."
      )
      return
    }
    consumeAwaitedReplyFn(awaitKey)
    completeLeave(linkId, data.linkLength, state)
  }

  /** A plain MESO link only repeats the length the vehicle stored at entry, so the vehicle completes the leave itself. */
  def leaveWithoutReply(linkId: Long, state: MotorcycleState): Unit =
    completeLeave(linkId, state.mesoLinkLength, state)

  private def completeLeave(linkId: Long, linkLength: Double, state: MotorcycleState): Unit = {
    state.distance += linkLength
    journeyReporter.sumoArrivalSpeed = 0.0
    journeyReporter.sumoArrivalLane  = Some(s"${linkId}_0")
    journeyReporter.sumoArrivalPos   = linkLength
    journeyReporter.updateHaltingState(0.0, 0.0)
    setMesoExitTickFn(None)
    state.leaveAckRequired = true
    state.leaveHandledByLink = false
    val linkOwnedExit = state.linkOwnedExit
    state.linkOwnedExit = false
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
      onFinishSpontaneousFn(Some(if (model.mobility.util.VehicleSimulationConfig.mesoImmediateNodeTransfer && !linkOwnedExit) currentTickFn() else currentTickFn() + 1))
    }
  }
}
