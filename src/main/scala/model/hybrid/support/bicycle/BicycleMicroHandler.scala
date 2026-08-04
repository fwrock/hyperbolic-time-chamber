package org.interscity.htc
package model.hybrid.support.bicycle

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.{
  MicroEnterLinkData,
  MicroLeaveLinkData,
  MicroUpdateData
}
import org.interscity.htc.model.hybrid.entity.state.{ BicycleState, MicroBicycleState }
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Moving

class BicycleMicroHandler(
  reportFn:                (Map[String, Any], String) => Unit,
  entityIdFn:              () => String,
  currentTickFn:           () => Tick,
  journeyReporter:         BicycleJourneyReporter,
  requestSignalStateFn:    () => Unit,
  onFinishSpontaneousFn:   Option[Tick] => Unit,
  onFinishPrivateVehicleFn: String => Unit,
  selfDestructFn:          () => Unit,
  isPersonCentricFn:       () => Boolean,
  finishJourneyFn:         (String, String) => Unit,
  logDebugFn:              String => Unit,
  setCurrentLinkIdFn:      Option[String] => Unit,
  setLinkEntryTickFn:      Option[Tick] => Unit,
  getLinkEntryTickFn:      () => Option[Tick],
  getCurrentLinkIdFn:      () => Option[String],
  microUpdateReportEvery:  Int = 0
) {

  private var microUpdateReportCount: Long = 0L

  def handleMicroEnterLink(data: MicroEnterLinkData, state: BicycleState): Unit = {
    logDebugFn(s"Bicycle entering MICRO link ${data.linkId}, lane ${data.assignedLane}")
    setCurrentLinkIdFn(Some(data.linkId))
    setLinkEntryTickFn(Some(currentTickFn()))

    val bikeLane = if (data.numberOfLanes >= 3) Some(0) else None
    val initialMicroState = MicroBicycleState(
      positionInLink   = 0.0,
      velocity         = 5.0,
      acceleration     = 0.0,
      currentLane      = bikeLane.getOrElse(data.assignedLane),
      leaderVehicle    = None,
      gapToLeader      = data.linkLength,
      leaderVelocity   = 5.56,
      maxAcceleration  = 1.0,
      maxDeceleration  = 3.0,
      minGap           = 1.5,
      desiredVelocity  = 5.56,
      reactionTime     = 1.2,
      vehicleLength    = 2.0,
      prefersBikeLane  = true,
      canUseSidewalk   = false,
      desiredLane      = bikeLane,
      laneChangeProgress = 0.0
    )

    state.activateMicroMode(initialMicroState)
    state.status = Moving
    journeyReporter.sumoCurrentMicroTimeStepSeconds =
      math.max(0.001, data.microTimeStep)
    journeyReporter.sumoIdealTravelTimeSeconds +=
      data.linkLength / (math.max(0.1, data.speedLimit) / 3.6)
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
        "bicycle_id"      -> entityIdFn(),
        "link_id"         -> data.linkId,
        "mode"            -> "MICRO",
        "lane"            -> initialMicroState.currentLane,
        "prefers_bike_lane" -> initialMicroState.prefersBikeLane,
        "link_length"     -> data.linkLength,
        "initial_velocity" -> initialMicroState.velocity,
        "tick"            -> currentTickFn()
      ),
      "enter_micro_link"
    )

    onFinishSpontaneousFn(Some(currentTickFn() + 1))
  }

  def handleMicroUpdate(data: MicroUpdateData, state: BicycleState): Unit =
    state.microState.foreach { micro =>
      val updatedMicro = micro.copy(
        positionInLink = data.position,
        velocity       = data.velocity,
        acceleration   = data.acceleration,
        currentLane    = data.currentLane,
        leaderVehicle  = data.leaderVehicle,
        gapToLeader    = data.gapToLeader,
        leaderVelocity = data.leaderVelocity
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
            "event_type" -> "micro_update",
            "bicycle_id" -> entityIdFn(),
            "link_id"    -> getCurrentLinkIdFn().getOrElse(""),
            "mode"       -> "MICRO",
            "position"   -> data.position,
            "velocity"   -> data.velocity,
            "lane"       -> data.currentLane,
            "sub_tick"   -> data.subTick,
            "tick"       -> currentTickFn()
          ),
          "micro_update"
        )
      }
    }

  def handleMicroLeaveLink(data: MicroLeaveLinkData, state: BicycleState): Unit = {
    logDebugFn(s"Bicycle leaving MICRO link ${data.linkId}")
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
        "bicycle_id"        -> entityIdFn(),
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
}
