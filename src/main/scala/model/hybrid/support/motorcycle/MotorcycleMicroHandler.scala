package org.interscity.htc
package model.hybrid.support.motorcycle

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.{
  MicroEnterLinkData,
  MicroLeaveLinkData,
  MicroUpdateData
}
import org.interscity.htc.model.hybrid.entity.state.{ MicroMotorcycleState, MotorcycleState }
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Moving

class MotorcycleMicroHandler(
  reportFn:                (Map[String, Any], String) => Unit,
  entityIdFn:              () => String,
  currentTickFn:           () => Tick,
  journeyReporter:         MotorcycleJourneyReporter,
  requestSignalStateFn:    () => Unit,
  onFinishSpontaneousFn:   Option[Tick] => Unit,
  onFinishPrivateVehicleFn: String => Unit,
  selfDestructFn:          () => Unit,
  isPersonCentricFn:       () => Boolean,
  finishJourneyFn:         (String, String) => Unit,
  logDebugFn:              String => Unit,
  aggressivenessFn:        () => Double,
  setCurrentLinkIdFn:      Option[String] => Unit,
  setLinkEntryTickFn:      Option[Tick] => Unit,
  getLinkEntryTickFn:      () => Option[Tick],
  getCurrentLinkIdFn:      () => Option[String],
  microUpdateReportEvery:  Int = 0
) {

  private var microUpdateReportCount: Long = 0L

  def handleMicroEnterLink(data: MicroEnterLinkData, state: MotorcycleState): Unit = {
    logDebugFn(s"Motorcycle entering MICRO link ${data.linkId}, lane ${data.assignedLane}")
    setCurrentLinkIdFn(Some(data.linkId))
    setLinkEntryTickFn(Some(currentTickFn()))

    val agg = aggressivenessFn()
    // speedLimit from LinkState is stored in km/h; micro physics runs in m/s
    val speedLimitMs = data.speedLimit / 3.6
    val initialMicroState = MicroMotorcycleState(
      positionInLink       = 0.0,
      velocity             = speedLimitMs * 0.9,
      acceleration         = 0.0,
      currentLane          = data.assignedLane,
      leaderVehicle        = None,
      gapToLeader          = data.linkLength,
      leaderVelocity       = speedLimitMs,
      maxAcceleration      = 3.5,
      maxDeceleration      = 5.0,
      minGap               = 1.5,
      desiredVelocity      = math.min(speedLimitMs, 16.67),
      reactionTime         = 0.9,
      vehicleLength        = 2.5,
      canFilterLanes       = true,
      aggressiveness       = agg,
      desiredLane          = None,
      laneChangeProgress   = 0.0,
      filteringBetweenLanes = false
    )

    state.activateMicroMode(initialMicroState)
    state.status = Moving
    journeyReporter.sumoCurrentMicroTimeStepSeconds =
      math.max(0.001, data.microTimeStep)
    journeyReporter.sumoIdealTravelTimeSeconds +=
      data.linkLength / math.max(0.1, speedLimitMs)
    journeyReporter.updateHaltingState(initialMicroState.velocity, 0.0)
    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(currentTickFn())
      journeyReporter.sumoDepartSpeed = initialMicroState.velocity
      journeyReporter.sumoDepartLane  = Some(s"${data.linkId}_${initialMicroState.currentLane}")
      journeyReporter.sumoDepartPos   = 0.0
    }

    reportFn(
      Map(
        "event_type"      -> "enter_micro_link",
        "motorcycle_id"   -> entityIdFn(),
        "link_id"         -> data.linkId,
        "mode"            -> "MICRO",
        "lane"            -> initialMicroState.currentLane,
        "can_filter_lanes" -> initialMicroState.canFilterLanes,
        "aggressiveness"  -> initialMicroState.aggressiveness,
        "link_length"     -> data.linkLength,
        "initial_velocity" -> initialMicroState.velocity,
        "tick"            -> currentTickFn()
      ),
      "enter_micro_link"
    )

    onFinishSpontaneousFn(Some(currentTickFn() + 1))
  }

  def handleMicroUpdate(data: MicroUpdateData, state: MotorcycleState): Unit =
    state.microState.foreach { micro =>
      val isFiltering = shouldAttemptLaneFiltering(micro) && data.velocity < 5.0
      val updatedMicro = micro.copy(
        positionInLink        = data.position,
        velocity              = data.velocity,
        acceleration          = data.acceleration,
        currentLane           = data.currentLane,
        leaderVehicle         = data.leaderVehicle,
        gapToLeader           = data.gapToLeader,
        leaderVelocity        = data.leaderVelocity,
        filteringBetweenLanes = isFiltering
      )
      state.updateMicroState(updatedMicro)
      journeyReporter.sumoArrivalSpeed = data.velocity
      journeyReporter.updateHaltingState(
        data.velocity,
        journeyReporter.sumoCurrentMicroTimeStepSeconds
      )

      microUpdateReportCount += 1
      if (microUpdateReportEvery > 0 && microUpdateReportCount % microUpdateReportEvery == 0L) {
        reportFn(
          Map(
            "event_type"    -> "micro_update",
            "motorcycle_id" -> entityIdFn(),
            "link_id"       -> getCurrentLinkIdFn().getOrElse(""),
            "mode"          -> "MICRO",
            "position"      -> data.position,
            "velocity"      -> data.velocity,
            "lane"          -> data.currentLane,
            "sub_tick"      -> data.subTick,
            "tick"          -> currentTickFn()
          ),
          "micro_update"
        )
      }
    }

  def handleMicroLeaveLink(data: MicroLeaveLinkData, state: MotorcycleState): Unit = {
    logDebugFn(s"Motorcycle leaving MICRO link ${data.linkId}")
    val travelTime = getLinkEntryTickFn().map(e => currentTickFn() - e).getOrElse(0L)

    state.distance += data.distanceTraveled
    journeyReporter.sumoArrivalSpeed = data.finalVelocity
    journeyReporter.sumoArrivalLane =
      Some(s"${data.linkId}_${state.microState.map(_.currentLane).getOrElse(0)}")
    journeyReporter.sumoArrivalPos = data.finalPosition
    journeyReporter.updateHaltingState(data.finalVelocity, 0.0)

    reportFn(
      Map(
        "event_type"        -> "leave_micro_link",
        "motorcycle_id"     -> entityIdFn(),
        "link_id"           -> data.linkId,
        "mode"              -> "MICRO",
        "travel_time_ticks" -> travelTime,
        "distance_traveled" -> data.distanceTraveled,
        "average_speed"     -> data.averageSpeed,
        "total_distance"    -> state.distance,
        "tick"              -> currentTickFn()
      ),
      "leave_micro_link"
    )

    state.deactivateMicroMode()
    setCurrentLinkIdFn(None)
    setLinkEntryTickFn(None)
    requestSignalStateFn()
  }

  private def shouldAttemptLaneFiltering(micro: MicroMotorcycleState): Boolean = {
    if (!micro.canFilterLanes) return false
    val trafficIsSlow = micro.leaderVelocity < 8.33
    val gapIsSmall    = micro.gapToLeader < 20.0
    val isAggressive  = micro.aggressiveness > 0.5
    trafficIsSlow && gapIsSmall && isAggressive
  }
}
