package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.PersonState
import model.hybrid.entity.event.data.routing.ModeDecisionQueryData

enum PersonDecisionKind:
  case PendingPlan, PTTimeoutReplan

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

  /** The decision is consistency-critical and will be completed by a RoutingService reply. */
  final case class DecisionRequested(
    state: PersonState,
    query: ModeDecisionQueryData,
    kind: PersonDecisionKind
  ) extends PlanStepResult

  /** The plan has nothing left to run — schedule complete for the day. */
  final case class Finished(state: PersonState) extends PlanStepResult

}
