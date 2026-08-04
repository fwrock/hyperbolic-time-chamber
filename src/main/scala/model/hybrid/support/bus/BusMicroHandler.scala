package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.{MicroEnterLinkData, MicroLeaveLinkData, MicroUpdateData}
import org.interscity.htc.model.hybrid.entity.state.{BusState, MicroBusState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{Moving, Ready}

/** Handles all microscopic simulation events for Bus actors.
  *
  * Responsibilities:
  *   - MicroEnterLink: initialise MicroBusState, record depart info
  *   - MicroUpdate: kinematics update, bus-stop probing
  *   - MicroLeaveLink: accumulate distances, restore meso mode
  */
class BusMicroHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: BusJourneyReporter,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val logDebugFn: String => Unit,
  private val setCurrentLinkIdFn: Option[String] => Unit,
  private val setLinkEntryTickFn: Option[Tick] => Unit,
  private val getLinkEntryTickFn: () => Option[Tick],
  private val setCurrentStopNodeFn: Option[String] => Unit,
  private val getCurrentLinkLengthFn: () => Double,
  private val findNextBusStopFn: () => Option[String],
  private val checkBusStopAtPositionFn: Double => Unit,
  private val microUpdateLogEvery: Int,
  private val getCurrentLinkIdFn: () => Option[String] = () => None,
  private val microUpdateReportEvery: Int = 0
) {

  private var microUpdateLogCount: Long = 0L
  private var microUpdateReportCount: Long = 0L

  def handleMicroEnterLink(data: MicroEnterLinkData, state: BusState): Unit = {
    val tick     = currentTickFn()
    val entityId = entityIdFn()

    logDebugFn(s"Bus entering MICRO link ${data.linkId}, lane ${data.assignedLane}")

    setCurrentLinkIdFn(Some(data.linkId))
    setLinkEntryTickFn(Some(tick))

    // speedLimit from LinkState is stored in km/h; micro physics runs in m/s
    val speedLimitMs = data.speedLimit / 3.6
    val initialMicroState = MicroBusState(
      positionInLink  = 0.0,
      velocity        = state.microState.map(_.velocity).getOrElse(speedLimitMs * 0.7),
      acceleration    = 0.0,
      currentLane     = data.assignedLane,
      leaderVehicle   = None,
      gapToLeader     = data.linkLength,
      leaderVelocity  = speedLimitMs,
      maxAcceleration = 1.2,
      maxDeceleration = 3.5,
      minGap          = 3.0,
      desiredVelocity = math.min(speedLimitMs, 11.11) * math.max(0.5, math.min(1.5, state.speedFactor)),
      reactionTime    = 1.5,
      vehicleLength   = 12.0,
      capacity        = state.capacity,
      currentPassengers = state.people.size,
      nextBusStop     = findNextBusStopFn(),
      busLaneRestricted = true,
      desiredLane     = if (data.assignedLane == 2) Some(2) else None,
      laneChangeProgress = 0.0,
      canChangeLane   = false
    )

    state.activateMicroMode(initialMicroState)
    state.status = Moving
    journeyReporter.sumoCurrentMicroTimeStepSeconds = math.max(0.001, data.microTimeStep)
    journeyReporter.sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, speedLimitMs)
    journeyReporter.updateHaltingState(initialMicroState.velocity, 0.0)

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick  = Some(tick)
      journeyReporter.sumoDepartSpeed = initialMicroState.velocity
      journeyReporter.sumoDepartLane  = Some(s"${data.linkId}_${initialMicroState.currentLane}")
      journeyReporter.sumoDepartPos   = 0.0
    }

    reportFn(
      Map(
        "event_type"       -> "enter_micro_link",
        "bus_id"           -> entityId,
        "link_id"          -> data.linkId,
        "mode"             -> "MICRO",
        "lane"             -> data.assignedLane,
        "passengers"       -> state.people.size,
        "capacity"         -> state.capacity,
        "occupancy"        -> state.occupancyPercentage,
        "link_length"      -> data.linkLength,
        "initial_velocity" -> initialMicroState.velocity,
        "tick"             -> tick
      ),
      "enter_micro_link"
    )

    onFinishSpontaneousFn(Some(tick + 1))
  }

  def handleMicroUpdate(data: MicroUpdateData, state: BusState): Unit =
    state.microState.foreach { micro =>
      val updatedMicro = micro.copy(
        positionInLink    = data.position,
        velocity          = data.velocity,
        acceleration      = data.acceleration,
        currentLane       = data.currentLane,
        leaderVehicle     = data.leaderVehicle,
        gapToLeader       = data.gapToLeader,
        leaderVelocity    = data.leaderVelocity,
        currentPassengers = state.people.size
      )
      state.updateMicroState(updatedMicro)
      journeyReporter.sumoArrivalSpeed = data.velocity
      journeyReporter.updateHaltingState(data.velocity, journeyReporter.sumoCurrentMicroTimeStepSeconds)

      microUpdateLogCount += 1
      if (microUpdateLogCount % microUpdateLogEvery == 0L)
        logDebugFn(s"Bus micro update[$microUpdateLogCount]: pos=${data.position}, vel=${data.velocity}, passengers=${state.people.size}")

      microUpdateReportCount += 1
      if (microUpdateReportEvery > 0 && microUpdateReportCount % microUpdateReportEvery == 0L) {
        reportFn(
          Map(
            "event_type" -> "micro_update",
            "bus_id"     -> entityIdFn(),
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

      checkBusStopAtPositionFn(data.position)
    }

  def handleMicroLeaveLink(data: MicroLeaveLinkData, state: BusState): Unit = {
    logDebugFn(s"Bus leaving MICRO link ${data.linkId}")

    val travelTime = getLinkEntryTickFn().map(currentTickFn() - _).getOrElse(0L)

    state.distance += data.distanceTraveled
    journeyReporter.sumoArrivalSpeed = data.finalVelocity
    journeyReporter.sumoArrivalLane  = Some(s"${data.linkId}_${state.microState.map(_.currentLane).getOrElse(0)}")
    journeyReporter.sumoArrivalPos   = data.finalPosition
    journeyReporter.updateHaltingState(data.finalVelocity, 0.0)

    reportFn(
      Map(
        "event_type"        -> "leave_micro_link",
        "bus_id"            -> entityIdFn(),
        "link_id"           -> data.linkId,
        "mode"              -> "MICRO",
        "passengers"        -> state.people.size,
        "occupancy"         -> state.occupancyPercentage,
        "travel_time_ticks" -> travelTime,
        "distance_traveled" -> data.distanceTraveled,
        "average_speed"     -> data.averageSpeed,
        "total_distance"    -> state.distance,
        "tick"              -> currentTickFn()
      ),
      "leave_micro_link"
    )

    // Capture destination node before deactivating micro state so actSpontaneous(Ready)
    // can check for a bus stop.
    setCurrentStopNodeFn(state.currentPath.map(_._2))

    state.deactivateMicroMode()
    state.status = Ready
    setCurrentLinkIdFn(None)
    setLinkEntryTickFn(None)

    onFinishSpontaneousFn(Some(currentTickFn() + 1))
  }
}
