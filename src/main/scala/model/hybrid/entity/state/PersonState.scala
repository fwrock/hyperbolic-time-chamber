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
  * For private vehicles (car, bicycle, motorcycle) there are no access/egress legs; the penalty is
  * applied to the direct origin→destination distance:
  * {{{U(private) = betaMode × modePref(mode) − betaPrivateVehicle × haversine(origin, destination)}}}
  *
  * @param includedModes
  *   Set of modes that the strategy is allowed to evaluate. Only modes listed here become
  *   candidates. Private-vehicle modes (`"car"`, `"bicycle"`, `"motorcycle"`) are additionally
  *   filtered by whether the person actually owns that vehicle at runtime.
  *   Defaults to `Set("bus", "subway", "walk")` (no private vehicles).
  * @param betaMode
  *   Scale applied to the mode-preference score.
  * @param betaAccess
  *   Per-metre penalty for the access leg (walking to boarding stop).
  * @param betaEgress
  *   Per-metre penalty for the egress leg (walking from alighting stop to destination).
  * @param betaPrivateVehicle
  *   Per-metre penalty applied to the direct O→D haversine distance for private vehicle modes.
  *   Typically lower than `betaAccess` since vehicles cover distance much faster than walking.
  * @param modePrefSubway
  *   Mode-preference utility for subway.
  * @param modePrefBus
  *   Mode-preference utility for bus.
  * @param modePrefWalk
  *   Mode-preference utility for walking (base reference, typically 0).
  * @param modePrefCar
  *   Mode-preference utility for car.
  * @param modePrefBicycle
  *   Mode-preference utility for bicycle.
  * @param modePrefMotorcycle
  *   Mode-preference utility for motorcycle.
  * @param maxAccessDistanceM
  *   Maximum acceptable access-leg distance in metres (stops further away are ignored).
  * @param maxWalkDistanceM
  *   Maximum trip distance in metres for which walking is offered as a candidate.
  *
  * ==== Travel-time strategy (`"travel-time"`) ====
  *
  * The [[model.hybrid.util.strategy.TravelTimeModeChoiceStrategy]] uses the following additional
  * fields to convert route cost into seconds of travel time. All other utility weights above still
  * apply (modePref, betaMode).
  *
  * @param betaTravelTime
  *   Per-second penalty applied to estimated travel time. Replaces `betaAccess`/`betaEgress` for
  *   PT and `betaPrivateVehicle` for car/bicycle/motorcycle when the travel-time strategy is used.
  *   Defaults to `0.0025` (1 util point lost per 400 seconds of travel).
  * @param walkingSpeedMs
  *   Walking speed in m/s used to convert access/egress distances to seconds. Default 1.4 m/s.
  * @param avgBusSpeedMs
  *   Average in-vehicle bus speed in m/s used to estimate PT in-vehicle travel time when no
  *   real PT network routing is available. Default 6.94 m/s (≈ 25 km/h).
  * @param avgSubwaySpeedMs
  *   Average in-vehicle subway speed in m/s. Default 11.11 m/s (≈ 40 km/h).
  * @param avgBicycleSpeedMs
  *   Expected bicycle cruising speed in m/s used to normalise the A* dynamic cost into seconds.
  *   Default 5.0 m/s (≈ 18 km/h).
  * @param avgMotorcycleSpeedMs
  *   Expected motorcycle cruising speed in m/s. Default 12.5 m/s (≈ 45 km/h).
  * @param avgCarSpeedMs
  *   Expected car cruising speed in m/s used when the A* dynamic cost must be converted to
  *   seconds (fallback if dynamic weights are unavailable). Default 13.89 m/s (≈ 50 km/h).
  *
  * ==== Default calibration provenance ====
  *
  * These defaults are literature-informed priors (not city-calibrated coefficients):
  *
  *   - `walkingSpeedMs = 1.4`: pedestrian free-flow speed from pedestrian-flow literature
  *     (Weidmann, 1993; Bohannon & Andrews, 2011; TRB HCM 2010).
  *   - `betaTravelTime = 0.0025`: initial per-second utility penalty used as a conservative prior
  *     for travel-time-sensitive mode choice (to be calibrated from observed modal split).
  *   - `betaAccess` / `betaEgress`: derived from `betaTravelTime` with a 2.0x walk-time penalty
  *     using `betaWalkDist = betaTravelTime * walkPenalty / walkingSpeedMs`.
  *   - `betaPrivateVehicle`: derived from `betaTravelTime / avgCarSpeedMs` for dimensional
  *     consistency in the utility strategy.
  *
  * For details and references, see `docs/PERSON_AGENT.md` (section 6.6).
  */
case class ModeChoiceWeights(
  includedModes: Set[String] = Set("car", "bicycle", "motorcycle", "bus", "subway", "walk"),
  betaMode: Double = 1.0,
  betaAccess: Double = 0.0036,
  betaEgress: Double = 0.0036,
  betaPrivateVehicle: Double = 0.00018,
  modePrefSubway: Double = 2.0,
  modePrefBus: Double = 1.0,
  modePrefWalk: Double = 0.0,
  modePrefCar: Double = 3.0,
  modePrefBicycle: Double = 1.5,
  modePrefMotorcycle: Double = 2.0,
  maxAccessDistanceM: Double = 1500.0,
  maxWalkDistanceM: Double = 2000.0,
  // travel-time strategy fields
  betaTravelTime: Double = 0.0025,
  walkingSpeedMs: Double = 1.4,
  avgBusSpeedMs: Double = 6.94,
  avgSubwaySpeedMs: Double = 11.11,
  avgBicycleSpeedMs: Double = 5.0,
  avgMotorcycleSpeedMs: Double = 12.5,
  avgCarSpeedMs: Double = 13.89
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
  * @param ptWaitingSince
  *   Tick when the person registered at a PT stop and started waiting for the vehicle. `None` when
  *   not in a PT wait state. Used together with [[ptWaitTimeoutTicks]] to implement a safety
  *   timeout so persons never wait indefinitely for a bus/subway that never arrives.
  * @param ptWaitTimeoutTicks
  *   Maximum number of ticks to wait at a PT stop before giving up and advancing to the next
  *   activity. Defaults to 86400 (one full simulated day). Dead lines are ejected immediately via
  *   [[org.interscity.htc.model.hybrid.entity.event.data.bus.PTLineNotOperationalData]], so this
  *   timeout only fires for lines that are operational but whose bus never reaches the stop within
  *   the simulation window.
  * @param enableDynamicModeChoice
  *   When `true`, the person re-evaluates the transport mode at each trip departure using a
  *   utility-based model instead of following the static logistics in the activity schedule.
  *   Defaults to `false` to preserve the existing static-schedule behaviour.
  * @param modeChoiceWeights
  *   Weights and thresholds for the utility function used when dynamic mode choice is active.
  * @param modeChoiceStrategyType
  *   Name of the [[model.hybrid.util.strategy.ModeChoiceStrategy]] to use when an activity's
  *   `arrivalLogistics.mode` is `"auto"`. Must match a key registered in
  *   [[model.hybrid.util.strategy.ModeChoiceStrategyRegistry]]. Defaults to `"utility"`
  *   (additive utility function over bus / subway / walk).
  * @param vehicleCurrentNode
  *   Tracks the current road-network node where each owned private vehicle is parked
  *   (mode -> nodeId). When absent for a given mode the vehicle is assumed to be at the person's
  *   current activity location (i.e. the start of the first day). Updated after each private
  *   vehicle trip so that a car left at home is unavailable from work.
  * @param pendingTransferLegs
  *   Remaining transit legs for an ongoing multi-leg trip produced by the RAPTOR algorithm.
  *   Non-empty only between consecutive PT legs within the same trip (e.g. a bus+subway transfer).
  *   The [[model.hybrid.actor.Person]] actor pops legs from this list after each alighting instead
  *   of advancing to the next scheduled activity.
  */
case class PersonState(
  startTick: Tick = 0L,
  scheduleOnTimeManager: Boolean = true,
  dailySchedule: List[Activity] = List.empty,
  currentActivityIndex: Int = 0,
  ownedVehicles: Map[String, Identify] = Map.empty,
  vehicleCurrentNode: Map[String, String] = Map.empty,
  currentTripVehicleId: Option[String] = None,
  currentTripStartTick: Option[Tick] = None,
  currentTripMode: Option[String] = None,
  currentTripId: Option[String] = None,
  currentTripDepartureTick: Option[Tick] = None,
  currentTripExpectedDistance: Option[Double] = None,
  currentTripWaitTime: Option[Long] = None,
  totalDistanceTraveled: Double = 0.0,
  completedTrips: Int = 0,
  scheduleDelayOffsetTicks: Long = 0L,
  firstTripDelayTicks: Option[Long] = None,
  ptAlightingNodeId: Option[String] = None,
  ptLine: Option[String] = None,
  ptWaitingSince: Option[Long] = None,
  ptWaitTimeoutTicks: Long = 86400L,
  enableDynamicModeChoice: Boolean = false,
  modeChoiceWeights: ModeChoiceWeights = ModeChoiceWeights(),
  modeChoiceStrategyType: String = "utility",
  pendingTransferLegs: List[ArrivalLogistics] = Nil
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
      currentTripStartTick = Some(tick),
      currentTripDepartureTick = Some(tick)
    )

  /** Complete a trip.
    */
  def completeTrip(distanceTraveled: Double): PersonState =
    copy(
      currentTripVehicleId = None,
      currentTripStartTick = None,
      currentTripMode = None,
      currentTripId = None,
      currentTripDepartureTick = None,
      currentTripExpectedDistance = None,
      currentTripWaitTime = None,
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
  mode: String, // "car", "bicycle", "motorcycle", "walk", "transit", "bus", "subway", "auto"
  vehicle: Option[Identify] = None,
  instant: Boolean = false,
  driverAttributes: DriverAttributes = DriverAttributes(),
  line: Option[String] = None,
  boardingStopId: Option[String] = None,
  boardingStopClassType: Option[String] = None,
  alightingNodeId: Option[String] = None,
  fixedMode: Boolean = false,  // when true, skips dynamic mode choice even if the person flag is on
  precomputedRoute: Option[List[(String, String)]] = None  // route pre-computed by ModeChoiceStrategy; avoids double A*
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
