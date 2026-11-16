package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import model.hybrid.entity.state.model.JunctionGeometry
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
  private val finishAndCleanupFn: (String, Long) => Unit,
  private val onFinishPrivateVehicleFn: Long => Unit,
  private val selfDestructFn: () => Unit,
  private val finishJourneyFn: (String, Long) => Unit,
  private val logWarnFn: String => Unit,
  private val logDebugFn: String => Unit,
  private val setCurrentLinkIdFn: Option[Long] => Unit,
  private val setCurrentLinkLengthFn: Double => Unit,
  private val setLinkEntryTickFn: Option[Tick] => Unit,
  private val getLinkEntryTickFn: () => Option[Tick],
  private val getCurrentLinkIdFn: () => Option[Long],
  private val microUpdateReportEvery: Int = 0,
  private val runtimeOwnsHorizonFn: () => Boolean = () => false
) {

  private var microUpdateReportCount: Long = 0L

  def handleMicroEnterLink(data: MicroEnterLinkData, state: CarState): Unit = {
    require(data.queuedWaitSeconds.isFinite && data.queuedWaitSeconds >= 0.0, "invalid MICRO admission wait")
    if (journeyReporter.sumoDepartTick.nonEmpty && data.queuedWaitSeconds > 0.0)
      journeyReporter.updateHaltingState(0.0,data.queuedWaitSeconds)
    val tick     = currentTickFn()
    val entityId = entityIdFn()
    if (!state.movableCurrentPathIsJunction) state.journey.pendingRoadLane = None

    setCurrentLinkIdFn(Some(data.linkId))
    setCurrentLinkLengthFn(data.linkLength)
    setLinkEntryTickFn(Some(tick))

    // speedLimit from LinkState is stored in km/h; Link micro physics converts with /3.6
    val speedLimitMs = data.speedLimit / 3.6
    val desiredSpeedMs = model.hybrid.entity.state.model.MicroSpeed.limit(speedLimitMs,
      state.microMaxSpeed,state.microSpeedFactor,state.microDesiredMaxSpeed)

    val initialMicroState = MicroCarState(
      positionInLink = data.initialPosition,
      velocity       = math.min(desiredSpeedMs,
        data.initialVelocity.getOrElse(state.microState.map(_.velocity).getOrElse(journeyReporter.sumoArrivalSpeed))),
      acceleration   = 0.0,
      currentLane    = data.assignedLane,
      leaderVehicle  = None,
      gapToLeader    = data.linkLength,
      leaderVelocity = speedLimitMs,
      desiredVelocity = desiredSpeedMs,
      minGap = state.microMinGap.getOrElse(2.0),
      reactionTime = state.microReactionTime.getOrElse(1.0),
      maxAcceleration = state.microAcceleration.getOrElse(2.6),
      maxDeceleration = state.microDeceleration.getOrElse(4.5),
      vehicleLength = state.size
    )

    state.activateMicroMode(initialMicroState)
    state.status = Moving
    state.journey.secondsPerTick = data.microTimeStep * data.ticksPerGlobalTick
    journeyReporter.sumoCurrentMicroTimeStepSeconds = math.max(0.001, data.microTimeStep)
    // Ideal travel time: length(m) / speed(m/s)
    val endPosition = if (!state.movableCurrentPathIsJunction && state.movableBestRoute.forall(_.isEmpty))
      state.microArrivalPosition.getOrElse(data.linkLength) else data.linkLength
    journeyReporter.sumoIdealTravelTimeSeconds += (endPosition - data.initialPosition) / math.max(0.1, desiredSpeedMs)

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(tick)
      journeyReporter.sumoDepartSpeed = initialMicroState.velocity
      journeyReporter.sumoDepartLane  = Some(s"${data.linkId}_${initialMicroState.currentLane}")
      journeyReporter.sumoDepartPos   = data.initialPosition
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
        "initial_position"      -> data.initialPosition,
        "min_gap"               -> initialMicroState.minGap,
        "reaction_time"         -> initialMicroState.reactionTime,
        "desired_velocity"      -> initialMicroState.desiredVelocity,
        "speed_factor"          -> state.microSpeedFactor,
        "driver_sigma"          -> state.microSigma,
        "driver_random_seed"    -> state.microRandomSeed,
        "lc2013_strategic"       -> state.microLc2013.strategic,
        "lc2013_cooperative"     -> state.microLc2013.cooperative,
        "lc2013_speed_gain"      -> state.microLc2013.speedGain,
        "lc2013_keep_right"      -> state.microLc2013.keepRight,
        "admission_wait_seconds" -> data.queuedWaitSeconds,
        "micro_time_step"       -> data.microTimeStep,
        "ticks_per_global_tick" -> data.ticksPerGlobalTick,
        "tick"                  -> tick
      ) ++ model.hybrid.support.MicroObservationFields.event(tick) ++ JunctionGeometry.reportingFields(data.junctionPosition),
      "enter_micro_link"
    )

    onFinishSpontaneousFn(None)
  }

  def handleMicroUpdate(data: MicroUpdateData, state: CarState): Unit = {
    if (
      !runtimeOwnsHorizonFn() && !extendSimulationFn()
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
            "link_id"    -> getCurrentLinkIdFn().getOrElse(0L),
            "mode"       -> "MICRO",
            "position"   -> data.position,
            "velocity"   -> data.velocity,
            "lane"       -> data.currentLane,
            "sub_tick"   -> data.subTick,
            "tick"       -> currentTickFn()
          ) ++ model.hybrid.support.MicroObservationFields.update(data, currentTickFn(), microUpdateReportEvery) ++ JunctionGeometry.reportingFields(data.junctionPosition),
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
          s"(car is on link ${currentLinkId.getOrElse(0L)}). Discarded."
      )
      return
    }

    val travelTime = getLinkEntryTickFn().map(currentTickFn() - _).getOrElse(0L)

    state.movableLinkExitReported = true
    state.journey.retainedMicroExitLinkId = Option.when(data.retainedInLane)(data.linkId)
    state.journey.boundaryRemainderSeconds = data.remainingStepSeconds
    state.journey.boundaryExitTick = Some(currentTickFn())
    state.distance += data.distanceTraveled
    journeyReporter.sumoArrivalSpeed = data.finalVelocity
    val finalLane = data.finalLane.orElse(state.microState.map(_.currentLane)).getOrElse(0)
    state.journey.lastMicroLane = Some(finalLane)
    journeyReporter.sumoArrivalLane  = Some(s"${data.linkId}_$finalLane")
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
        "lane"                   -> finalLane,
        "final_velocity"         -> data.finalVelocity,
        "travel_time_ticks"      -> travelTime,
        "travel_time_seconds"    -> data.travelTime,
        "distance_traveled"      -> data.distanceTraveled,
        "average_speed"          -> data.averageSpeed,
        "waiting_time_seconds"   -> data.waitingTimeSeconds,
        "total_distance"         -> state.distance,
        "tick"                   -> currentTickFn()
      ) ++ model.hybrid.support.MicroObservationFields.event(currentTickFn()) ++ JunctionGeometry.reportingFields(data.junctionPosition),
      "leave_micro_link"
    )

    state.deactivateMicroMode()
    setCurrentLinkIdFn(None)
    setCurrentLinkLengthFn(0.0)
    setLinkEntryTickFn(None)

    requestSignalStateFn()
  }
}
