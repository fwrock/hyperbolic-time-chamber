package org.interscity.htc
package model.hybrid.entity.state

import core.entity.state.BaseState
import core.types.Tick
import org.htc.protobuf.core.entity.actor.Identify

/** Weights for the utility function used in dynamic mode choice.
  *
  * The utility of a mode option is:
  * {{{U = betaMode × modePref(mode) − betaAccess × accessDistM − betaEgress × egressDistM}}}
  *
  * @param betaMode
  *   Scale applied to the mode-preference score.
  * @param betaAccess
  *   Per-metre penalty for the access leg (walking to boarding stop).
  * @param betaEgress
  *   Per-metre penalty for the egress leg (walking from alighting stop to destination).
  * @param modePrefSubway
  *   Mode-preference utility for subway.
  * @param modePrefBus
  *   Mode-preference utility for bus.
  * @param modePrefWalk
  *   Mode-preference utility for walking (base reference, typically 0).
  * @param maxAccessDistanceM
  *   Maximum acceptable access-leg distance in metres (stops further away are ignored).
  * @param maxWalkDistanceM
  *   Maximum trip distance in metres for which walking is offered as a candidate.
  */
case class ModeChoiceWeights(
  betaMode: Double = 1.0,
  betaAccess: Double = 0.001,
  betaEgress: Double = 0.001,
  modePrefSubway: Double = 2.0,
  modePrefBus: Double = 1.0,
  modePrefWalk: Double = 0.0,
  maxAccessDistanceM: Double = 1500.0,
  maxWalkDistanceM: Double = 2000.0
)

/** Person state representing a person agent in the simulation.
  *
  * Person-centric model where the Person actor persists throughout the day and manages their daily
  * schedule, making mode choices and activating private vehicles (Car, Bicycle, Motorcycle) as
  * needed.
  *
  * @param dailySchedule
  *   List of activities scheduled for the day
  * @param currentActivityIndex
  *   Current activity being performed
  * @param ownedVehicles
  *   Map of vehicle references owned by this person (mode -> Identify with id + classType)
  * @param currentTripVehicleId
  *   Vehicle currently being used (if any)
  * @param currentTripStartTick
  *   When current trip started
  * @param totalDistanceTraveled
  *   Total distance traveled today (meters)
  * @param completedTrips
  *   Number of trips completed today
  * @param ptAlightingNodeId
  *   Node ID where Person should alight from current PT vehicle. Set when boarding a bus/subway,
  *   cleared on alighting.
  * @param ptLine
  *   Current PT line being used (e.g. "Bus Line 1"). Set when boarding, cleared on alighting.
  * @param enableDynamicModeChoice
  *   When `true`, the person re-evaluates the transport mode at each trip departure using a
  *   utility-based model instead of following the static logistics in the activity schedule.
  *   Defaults to `false` to preserve the existing static-schedule behaviour.
  * @param modeChoiceWeights
  *   Weights and thresholds for the utility function used when dynamic mode choice is active.
  */
case class PersonState(
  startTick: Tick = 0L,
  scheduleOnTimeManager: Boolean = true,
  dailySchedule: List[Activity] = List.empty,
  currentActivityIndex: Int = 0,
  ownedVehicles: Map[String, Identify] = Map.empty,
  currentTripVehicleId: Option[String] = None,
  currentTripStartTick: Option[Tick] = None,
  totalDistanceTraveled: Double = 0.0,
  completedTrips: Int = 0,
  ptAlightingNodeId: Option[String] = None,
  ptLine: Option[String] = None,
  enableDynamicModeChoice: Boolean = false,
  modeChoiceWeights: ModeChoiceWeights = ModeChoiceWeights()
) extends BaseState(
      startTick = startTick,
      scheduleOnTimeManager = scheduleOnTimeManager
    ) {

  /** Get current activity.
    */
  def currentActivity: Option[Activity] =
    if (currentActivityIndex >= 0 && currentActivityIndex < dailySchedule.length) {
      Some(dailySchedule(currentActivityIndex))
    } else {
      None
    }

  /** Get next activity.
    */
  def nextActivity: Option[Activity] = {
    val nextIndex = currentActivityIndex + 1
    if (nextIndex < dailySchedule.length) {
      Some(dailySchedule(nextIndex))
    } else {
      None
    }
  }

  /** Advance to next activity.
    */
  def advanceActivity(): PersonState =
    copy(currentActivityIndex = currentActivityIndex + 1)

  /** Check if person has completed all activities.
    */
  def isScheduleComplete: Boolean =
    currentActivityIndex >= dailySchedule.length

  /** Start a trip.
    */
  def startTrip(vehicleId: String, tick: Tick): PersonState =
    copy(
      currentTripVehicleId = Some(vehicleId),
      currentTripStartTick = Some(tick)
    )

  /** Complete a trip.
    */
  def completeTrip(distanceTraveled: Double): PersonState =
    copy(
      currentTripVehicleId = None,
      currentTripStartTick = None,
      totalDistanceTraveled = totalDistanceTraveled + distanceTraveled,
      completedTrips = completedTrips + 1,
      ptAlightingNodeId = None,
      ptLine = None
    )
}

/** Activity in a person's daily schedule.
  *
  * @param sequence
  *   Order in the schedule (0-based)
  * @param activityType
  *   Type of activity ("Home", "Work", "School", "Shopping", etc.)
  * @param nodeId
  *   Location node ID
  * @param endTime
  *   When this activity ends (format: "HH:MM" or tick number)
  * @param arrivalLogistics
  *   How to arrive at this location (None for first activity)
  */
case class Activity(
  sequence: Int,
  activityType: String,
  nodeId: String,
  endTime: String, // Could be "08:00" or tick number as string
  arrivalLogistics: Option[ArrivalLogistics] = None
)

/** Logistics for arriving at an activity location.
  *
  * @param mode
  *   Transportation mode ("car", "bicycle", "motorcycle", "walk", "transit", "bus", "subway")
  * @param vehicle
  *   Complete vehicle reference with id and classType (for private modes)
  * @param instant
  *   If true, skip routing — origin and destination are the same node or the trip should be treated
  *   as zero-time (e.g. already-at-destination activities after PT stop snapping collapsed two
  *   consecutive stops to the same road node)
  * @param driverAttributes
  *   Attributes affecting driving behavior
  * @param line
  *   Bus/subway line label (e.g. "Bus Line 1"). Required for PT modes.
  * @param boardingStopId
  *   Actor ID of the BusStop/SubwayStation where Person boards (e.g. "htcaid:busstop;busstop_123").
  *   Required for PT modes.
  * @param boardingStopClassType
  *   Class type of the boarding stop actor (e.g. "hybrid.actor.BusStop"). Required for PT modes.
  * @param alightingNodeId
  *   Node ID where Person should alight (destination node for this PT leg). Required for PT modes.
  *   The Person responds isArrival=true to unload requests at this node.
  */
case class ArrivalLogistics(
  mode: String, // "car", "bicycle", "motorcycle", "walk", "transit", "bus", "subway"
  vehicle: Option[Identify] = None,
  instant: Boolean = false,
  driverAttributes: DriverAttributes = DriverAttributes(),
  line: Option[String] = None,
  boardingStopId: Option[String] = None,
  boardingStopClassType: Option[String] = None,
  alightingNodeId: Option[String] = None,
  fixedMode: Boolean = false   // when true, skips dynamic mode choice even if the person flag is on
)

/** Driver attributes affecting vehicle behavior.
  *
  * These parameters override default vehicle physics when a person activates a vehicle.
  *
  * @param aggressiveness
  *   How aggressive the driver is [0.0 - 1.0]
  * @param maxSpeedFactor
  *   Multiplier for speed limit adherence [0.5 - 1.5]
  * @param reactionTime
  *   Reaction time in seconds [0.5 - 2.0]
  * @param minGapFactor
  *   Multiplier for minimum safe gap [0.5 - 2.0]
  */
case class DriverAttributes(
  aggressiveness: Double = 0.5,
  maxSpeedFactor: Double = 1.0,
  reactionTime: Double = 1.0,
  minGapFactor: Double = 1.0
) {

  /** Validate attributes are within acceptable ranges.
    */
  def validate(): DriverAttributes =
    copy(
      aggressiveness = math.max(0.0, math.min(1.0, aggressiveness)),
      maxSpeedFactor = math.max(0.5, math.min(1.5, maxSpeedFactor)),
      reactionTime = math.max(0.5, math.min(2.0, reactionTime)),
      minGapFactor = math.max(0.5, math.min(2.0, minGapFactor))
    )
}
