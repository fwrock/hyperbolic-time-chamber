package org.interscity.htc
package model.hybrid.entity.state

import core.entity.state.BaseState
import core.types.Tick
import core.util.StringPool
import enumeration.TravelMode
import org.interscity.htc.model.hybrid.entity.state.plan.{ Activity as PlanActivity, ExecutedElement, PendingDecision, PlanCursor, PlanElement, PrivateVehicleLeg, RemainingQueue, TransitLeg, WalkLeg }
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
  * Person-centric model where the Person actor persists throughout the day, walking a
  * [[model.hybrid.entity.state.plan.PlanCursor]] one [[model.hybrid.entity.state.plan.PlanElement]]
  * at a time — see `model.hybrid.support.person.PersonPlanManager` for the orchestration and
  * `docs/PERSON_AGENT.md` for the full model description.
  *
  * @param originalPlan
  *   The plan exactly as loaded from the scenario, in order. Immutable for the lifetime of the
  *   actor — kept only for provenance/debugging (e.g. reporting what was originally scheduled vs.
  *   what actually executed after replans). [[cursor]], not this field, drives execution.
  * @param cursor
  *   The person's actual position within the plan: what has executed so far and what's left to
  *   run. Starts as `PlanCursor(Nil, RemainingQueue(originalPlan))` and only ever advances via
  *   `PlanCursor.advance`/`expandPending`/`expandReplan` — this is the sole source of truth for
  *   "what activity/leg is the person on right now", replacing the old `dailySchedule` +
  *   `currentActivityIndex` pair.
  * @param tripExecution
  *   Whether a leg is currently in flight and, if so, everything needed to resume/recover it. See
  *   [[TripExecutionState]] — this single field replaces every one of the pre-redesign
  *   `PersonState`'s ad hoc `currentTripXxx`/`ptXxx`/`pendingTransferLegs`/`currentPhysicalNodeId`
  *   fields.
  * @param ownedVehicles
  *   Map of vehicle references owned by this person (mode -> Identify with id + classType).
  * @param vehicleCurrentNode
  *   Tracks the current road-network node where each owned private vehicle is parked
  *   (mode -> nodeId). When absent for a given mode the vehicle is assumed to be at the person's
  *   current activity location (i.e. the start of the first day). Updated after each private
  *   vehicle trip so that a car left at home is unavailable from work.
  * @param totalDistanceTraveled
  *   Total distance traveled today (meters). Only private-vehicle legs contribute a real,
  *   non-zero distance here (reported by the vehicle actor itself on `TripCompletedData`) — this
  *   mirrors the pre-redesign behaviour, where walk/PT legs always completed with `distance = 0.0`.
  * @param completedTrips
  *   Number of trips completed today. Incremented once per contiguous leg run (not once per leg) —
  *   a multi-leg walk+transit+walk journey counts as a single trip.
  * @param ptWaitTimeoutTicks
  *   Maximum number of ticks to wait at a PT stop before giving up and triggering a replan (see
  *   `PersonPlanManager.replanAfterPTTimeout`). Defaults to 86400 (one full simulated day). Dead
  *   lines are ejected immediately via
  *   [[org.interscity.htc.model.hybrid.entity.event.data.bus.PTLineNotOperationalData]], so this
  *   timeout only fires for lines that are operational but whose vehicle never reaches the stop
  *   within the simulation window.
  * @param modeChoiceWeights
  *   Default utility weights used as `DecisionContext.weights` for every
  *   `model.hybrid.decision.ModeDecisionEngine.decide` call, unless a specific
  *   `PendingDecision.decision.weightsOverride` is present.
  */
case class PersonState(
  startTick: Tick = 0L,
  scheduleOnTimeManager: Boolean = true,
  originalPlan: List[PlanElement] = List.empty,
  cursor: PlanCursor = PlanCursor(executed = Nil, remaining = RemainingQueue(Nil)),
  tripExecution: TripExecutionState = TripExecutionState.Idle,
  ownedVehicles: Map[String, Identify] = Map.empty,
  vehicleCurrentNode: Map[String, String] = Map.empty,
  totalDistanceTraveled: Double = 0.0,
  completedTrips: Int = 0,
  ptWaitTimeoutTicks: Long = 86400L,
  modeChoiceWeights: ModeChoiceWeights = ModeChoiceWeights()
) extends BaseState(
      startTick = startTick,
      scheduleOnTimeManager = scheduleOnTimeManager
    ) {

  /** Road-network node the person is physically at right now.
    *
    * While traveling, this is [[TripExecutionState.Traveling.physicalNodeId]]. While idle
    * (dwelling at an activity, or before the plan has started), it is the most recently executed
    * [[model.hybrid.entity.state.plan.Activity]]'s node — the same node the person will depart
    * from once [[model.hybrid.entity.state.plan.LatenessPolicy]] resolves the departure tick.
    */
  def currentPhysicalNodeId: Option[String] =
    tripExecution match {
      case t: TripExecutionState.Traveling => Some(t.physicalNodeId)
      case TripExecutionState.Idle =>
        cursor.executed.reverseIterator.collectFirst { case a: PlanActivity => a.nodeId }
    }

  /** Returns a copy with all high-duplication string fields replaced by shared StringPool
    * instances. Call once per actor at initialization time to deduplicate activity-type strings
    * ("home", "work"), node IDs, line names, and PT stop IDs that are identical across many Person
    * actors at city scale.
    */
  def withInternedStrings: PersonState =
    copy(
      originalPlan = originalPlan.map(PersonState.internPlanElement),
      cursor = cursor.copy(
        executed = cursor.executed.map(PersonState.internExecuted),
        remaining = RemainingQueue(
          PersonState.drainRemaining(cursor.remaining).map(PersonState.internPlanElement)
        )
      )
    )

  /** True once the plan cursor has nothing left to run and nothing currently in flight. */
  def isScheduleComplete: Boolean =
    tripExecution == TripExecutionState.Idle && cursor.remaining.isEmpty

  /** Record the completion of one contiguous leg run (a "trip"): bump the trip counter and add any
    * real distance traveled, then return to [[TripExecutionState.Idle]].
    */
  def completeTrip(distanceTraveled: Double): PersonState =
    copy(
      totalDistanceTraveled = totalDistanceTraveled + distanceTraveled,
      completedTrips = completedTrips + 1,
      tripExecution = TripExecutionState.Idle
    )
}

object PersonState {

  private def drainRemaining(queue: RemainingQueue): List[PlanElement] =
    queue.dequeue match {
      case None            => Nil
      case Some((h, rest)) => h :: drainRemaining(rest)
    }

  private def internExecuted(e: ExecutedElement): ExecutedElement = e match {
    case a: PlanActivity =>
      a.copy(activityType = StringPool.intern(a.activityType), nodeId = StringPool.intern(a.nodeId))
    case w: WalkLeg =>
      w.copy(
        originNodeId = StringPool.intern(w.originNodeId),
        destinationNodeId = StringPool.intern(w.destinationNodeId)
      )
    case t: TransitLeg =>
      t.copy(
        line = StringPool.intern(t.line),
        boardingStop = t.boardingStop.copy(
          actorId = StringPool.intern(t.boardingStop.actorId),
          actorClassType = StringPool.intern(t.boardingStop.actorClassType),
          nodeId = StringPool.intern(t.boardingStop.nodeId)
        ),
        alightingStop = t.alightingStop.copy(
          actorId = StringPool.intern(t.alightingStop.actorId),
          actorClassType = StringPool.intern(t.alightingStop.actorClassType),
          nodeId = StringPool.intern(t.alightingStop.nodeId)
        )
      )
    case p: PrivateVehicleLeg => p
  }

  private def internPlanElement(e: PlanElement): PlanElement = e match {
    case ex: ExecutedElement => internExecuted(ex)
    case d: PendingDecision  => d
  }
}

/** Logistics for arriving at an activity location.
  *
  * Retained as the internal input/output shape of the pre-existing, already-audited routing
  * implementations ([[model.hybrid.util.ModeChoiceUtil]],
  * [[model.hybrid.util.strategy.TravelTimeModeChoiceStrategy]]) that
  * `model.hybrid.decision.ModeDecisionEngine`s wrap — see
  * `model.hybrid.decision.ArrivalLogisticsTranslation`. No longer used directly by
  * `PersonState`/`Person` (superseded by `model.hybrid.entity.state.plan.AtomicLeg`), so this type
  * now lives purely as that internal seam.
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
) {
  /** Type-safe view of [[mode]]. Use this for match expressions instead of raw strings. */
  def travelMode: TravelMode = TravelMode.fromString(mode)

  /** Returns a copy with high-duplication string fields replaced by shared pool instances. */
  def interned: ArrivalLogistics = copy(
    mode                  = StringPool.intern(mode),
    line                  = StringPool.internOpt(line),
    boardingStopId        = StringPool.internOpt(boardingStopId),
    boardingStopClassType = StringPool.internOpt(boardingStopClassType),
    alightingNodeId       = StringPool.internOpt(alightingNodeId)
  )
}

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
