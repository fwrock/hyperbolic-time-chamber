package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.plan.ConcreteMode

import com.fasterxml.jackson.annotation.{ JsonSubTypes, JsonTypeInfo }

final case class WalkingLegProgress(distance: Double, arrivalTick: Tick)

/** Snapshot of a PT wait in progress: [[model.hybrid.actor.Person]] registered at a boarding stop
  * and is waiting for the corresponding bus/subway to actually arrive and load it.
  *
  * @param waitingSinceTick tick the person registered at the stop
  * @param timeoutTick      tick at which Person gives up waiting and triggers a replan (see
  *                         `PersonPlanManager.replanAfterPTTimeout`)
  * @param alightingNodeId  node where Person should alight once boarded — matched against every
  *                         unload request from the vehicle
  * @param line             PT line label, kept only for reporting/metrics
  */
final case class PTWaitState(
  waitingSinceTick: Tick,
  timeoutTick: Tick,
  alightingNodeId: Long,
  line: String,
  boardedVehicleId: Option[Long] = None,
  boardedVehicleClassType: Option[String] = None,
  boardingDistance: Option[Double] = None,
  boardingTick: Option[Tick] = None
)

/** What a [[model.hybrid.actor.Person]] is doing right now with respect to its plan.
  *
  * Replaces the ~15 loose trip-tracking fields the pre-redesign `PersonState` carried
  * (`currentTripVehicleId`, `currentTripMode`, `currentTripId`, `currentTripDepartureTick`,
  * `currentTripExpectedDistance`, `currentTripWaitTime`, `ptAlightingNodeId`, `ptLine`,
  * `ptWaitingSince`, `pendingTransferLegs`, `currentPhysicalNodeId`,
  * `currentTripDestinationNodeId`, ...) with a single sum type. `PersonState.cursor` (not part of
  * this type — see that field's own doc) tracks the plan *position*; `TripExecutionState` tracks
  * whether a leg is currently in flight and, if so, what a person waiting on it needs to resume or
  * recover.
  */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[TripExecutionState.Traveling], name = "Traveling"),
    new JsonSubTypes.Type(value = classOf[TripExecutionState.Idle.type], name = "Idle")
  )
)
sealed trait TripExecutionState

object TripExecutionState {

  /** Not currently mid-leg: either dwelling at an [[model.hybrid.entity.state.plan.Activity]]
    * (waiting for [[model.hybrid.entity.state.plan.LatenessPolicy]]'s departure tick) or the plan
    * hasn't started yet.
    */
  case object Idle extends TripExecutionState

  /** A leg is currently in flight.
    *
    * @param physicalNodeId     road-network node where the person physically is right now. For a
    *                           private-vehicle leg this is nominal (the vehicle actor tracks the
    *                           real position); for a walk leg it is the leg's origin until arrival;
    *                           for a transit leg it is the boarding stop's node until alighting.
    * @param tripId             identifier shared by every leg of the same trip (a trip may be a
    *                           contiguous run of several `AtomicLeg`s — access walk, transit
    *                           ride(s), egress walk — all sharing one `tripId`).
    * @param legStartTick       tick the *current* leg started (used for travel-time metrics on
    *                           arrival, and as the wait-time base for a PT leg).
    * @param ptWait             present only while waiting to board a bus/subway; see [[PTWaitState]].
    * @param replanStrategyId   the `ModeDecisionEngine` id to re-consult if this trip's current PT
    *                           leg times out. Carried here (rather than re-derived) because the
    *                           originating `PendingDecision` is consumed/discarded once its legs are
    *                           spliced into the plan by `PlanCursor.expandPending` — see
    *                           `PersonPlanManager` for the documented default used for legs that
    *                           were never behind a `PendingDecision` in the first place (a "fixed"
    *                           leg already concrete in the original plan).
    * @param replanAllowedModes the `allowedModes` to re-consult with, same rationale as
    *                           `replanStrategyId`.
    */
  final case class Traveling(
    physicalNodeId: Long,
    tripId: String,
    legStartTick: Tick,
    ptWait: Option[PTWaitState] = None,
    replanStrategyId: String = "travel-time",
    replanAllowedModes: Set[ConcreteMode] = ConcreteMode.values.toSet,
    /** Completed-leg distance, flushed into daily distance at the next Activity or abort.
      * An in-flight walking leg stays separate until its arrival is processed.
      */
    accumulatedDistance: Double = 0.0,
    walking: Option[WalkingLegProgress] = None
  ) extends TripExecutionState
}
