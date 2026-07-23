package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.PersonState

/** Outcome of asking [[PersonPlanManager]] to advance a person's plan by one step.
  *
  * Mirrors the pre-redesign `TripStartResult` ADT so `Person` can keep the same
  * pattern-match-and-dispatch shape it always had — only the cases themselves changed to match
  * the new plan-cursor model.
  */
sealed trait PlanStepResult

object PlanStepResult {

  /** Dwelling at an [[model.hybrid.entity.state.plan.Activity]]; schedule a self-wake at
    * `wakeTick` (the tick [[model.hybrid.entity.state.plan.LatenessPolicy]] resolved as the
    * departure tick).
    */
  final case class Awaiting(state: PersonState, wakeTick: Tick) extends PlanStepResult

  /** A leg was started.
    *
    * @param nextTick `Some(tick)` to self-schedule a wake at that tick (walk arrival, or a PT
    *                 wait timeout safety net); `None` when the timing is controlled by another
    *                 actor (a private vehicle's own `TripCompleted` notification).
    */
  final case class LegStarted(state: PersonState, nextTick: Option[Tick]) extends PlanStepResult

  /** The plan has nothing left to run — schedule complete for the day. */
  final case class Finished(state: PersonState) extends PlanStepResult

  // No `Stuck`/permanently-unscheduled case: a `PendingDecision` with no viable journey (unknown
  // strategyId, or `NoViableJourney`) is handled the same way as any other leg-initiation failure
  // — see `PersonPlanManager.abortPendingDecision` — aborting just that trip and resuming at the
  // next `Activity`, always producing one of the cases above. An earlier revision of this phase
  // had a dedicated `Stuck` case for that failure; it was removed once the user decided to
  // unify the two failure policies, since no code path produces it anymore.
}
