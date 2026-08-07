package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.{MicroEnterLinkData, MicroLeaveLinkData, MicroUpdateData}
import org.interscity.htc.model.hybrid.entity.state.{CarState, MicroCarState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Moving

/** Handles all microscopic simulation events for Car actors.
  *
  * Responsibilities:
  *   - MicroEnterLink: initialise micro state, record depart info
  *   - MicroUpdate: update kinematics per sub-tick
  *   - MicroLeaveLink: accumulate distances/waits, restore meso mode
  */
class CarMicroHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val simulationEndTickFn: () => Tick,
  private val extendSimulationFn: () => Boolean,
  private val journeyReporter: CarJourneyReporter,
  private val requestSignalStateFn: () => Unit,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val finishAndCleanupFn: (String, String) => Unit,
  private val onFinishPrivateVehicleFn: String => Unit,
  private val selfDestructFn: () => Unit,
  private val finishJourneyFn: (String, String) => Unit,
  private val logWarnFn: String => Unit,
  private val logDebugFn: String => Unit,
  private val setCurrentLinkIdFn: Option[String] => Unit,
  private val setCurrentLinkLengthFn: Double => Unit,
  private val setLinkEntryTickFn: Option[Tick] => Unit,
  private val getLinkEntryTickFn: () => Option[Tick],
  private val getCurrentLinkIdFn: () => Option[String],
  private val microUpdateReportEvery: Int = 0
) {

  private var microUpdateReportCount: Long = 0L

  def handleMicroEnterLink(data: MicroEnterLinkData, state: CarState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    setCurrentLinkIdFn(Some(data.linkId))
    setCurrentLinkLengthFn(data.linkLength)
    setLinkEntryTickFn(Some(tick))

    // speedLimit from LinkState is stored in km/h; Link micro physics converts with /3.6
    val speedLimitMs = data.speedLimit / 3.6

    val initialMicroState = MicroCarState(
      positionInLink = 0.0,
      velocity       = state.microState.map(_.velocity).getOrElse(journeyReporter.sumoArrivalSpeed),
      acceleration   = 0.0,
      currentLane    = data.assignedLane,
      leaderVehicle  = None,
      gapToLeader    = data.linkLength,
      leaderVelocity = speedLimitMs,
      desiredVelocity = speedLimitMs
    )

    state.activateMicroMode(initialMicroState)
    state.status = Moving
    journeyReporter.sumoCurrentMicroTimeStepSeconds = math.max(0.001, data.microTimeStep)
    // Ideal travel time: length(m) / speed(m/s)
    journeyReporter.sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, speedLimitMs)

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(tick)
      journeyReporter.sumoDepartSpeed = initialMicroState.velocity
      journeyReporter.sumoDepartLane  = Some(s"${data.linkId}_${initialMicroState.currentLane}")
      journeyReporter.sumoDepartPos   = 0.0
    }

    reportFn(
      Map(
        "event_type"            -> "enter_micro_link",
        "car_id"                -> entityId,
        "link_id"               -> data.linkId,
        "mode"                  -> "MICRO",
        "lane"                  -> data.assignedLane,
        "link_length"           -> data.linkLength,
        "speed_limit"           -> data.speedLimit,
        "initial_velocity"      -> initialMicroState.velocity,
        "micro_time_step"       -> data.microTimeStep,
        "ticks_per_global_tick" -> data.ticksPerGlobalTick,
        "tick"                  -> tick
      ),
      "enter_micro_link"
    )

    onFinishSpontaneousFn(None)
  }

  def handleMicroUpdate(data: MicroUpdateData, state: CarState): Unit = {
    if (
      !extendSimulationFn()
      && currentTickFn() >= simulationEndTickFn()
      && state.status != org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Finished
    ) {
      logDebugFn(
        s"Car ${entityIdFn()} exceeded simulation end time (${simulationEndTickFn()}) at tick ${currentTickFn()} in MICRO mode, force-finishing."
      )
      val finalNode = state.movableCurrentNode
      finishAndCleanupFn("simulation_time_exceeded", finalNode)
      return
    }

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

      microUpdateReportCount += 1
      if (microUpdateReportEvery > 0 && microUpdateReportCount % microUpdateReportEvery == 0L) {
        reportFn(
          Map(
            "event_type" -> "micro_update",
            "car_id"     -> entityIdFn(),
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
  }

  def handleMicroLeaveLink(data: MicroLeaveLinkData, state: CarState): Unit = {
    val currentLinkId = getCurrentLinkIdFn()
    if (!currentLinkId.contains(data.linkId)) {
      logWarnFn(
        s"${entityIdFn()}: Ignoring stale MicroLeaveLink for link ${data.linkId} " +
          s"(car is on link ${currentLinkId.getOrElse("none")}). Discarded."
      )
      return
    }

    val travelTime = getLinkEntryTickFn().map(currentTickFn() - _).getOrElse(0L)

    state.distance += data.distanceTraveled
    journeyReporter.sumoArrivalSpeed = data.finalVelocity
    journeyReporter.sumoArrivalLane  = Some(s"${data.linkId}_${state.microState.map(_.currentLane).getOrElse(0)}")
    journeyReporter.sumoArrivalPos   = data.finalPosition
    journeyReporter.sumoWaitingTimeSeconds += data.waitingTimeSeconds
    if (data.waitingTimeSeconds > 0.0) journeyReporter.sumoWaitingCount += 1

    reportFn(
      Map(
        "event_type"             -> "leave_micro_link",
        "car_id"                 -> entityIdFn(),
        "link_id"                -> data.linkId,
        "mode"                   -> "MICRO",
        "final_position"         -> data.finalPosition,
        "final_velocity"         -> data.finalVelocity,
        "travel_time_ticks"      -> travelTime,
        "travel_time_seconds"    -> data.travelTime,
        "distance_traveled"      -> data.distanceTraveled,
        "average_speed"          -> data.averageSpeed,
        "waiting_time_seconds"   -> data.waitingTimeSeconds,
        "total_distance"         -> state.distance,
        "tick"                   -> currentTickFn()
      ),
      "leave_micro_link"
    )

    state.deactivateMicroMode()
    setCurrentLinkIdFn(None)
    setCurrentLinkLengthFn(0.0)
    setLinkEntryTickFn(None)

    requestSignalStateFn()
  }
}
