package org.interscity.htc
package model.hybrid.entity.state.plan

/** A person's position within their plan: what has already run, and what's left to run. */
final case class PlanCursor(
  executed: List[ExecutedElement],
  remaining: RemainingQueue
)

/** Outcome of asking a [[PlanCursor]] to move to its next element. */
sealed trait AdvanceResult

/** The head element was executable and has been moved into `cursor.executed`. */
final case class Advanced(cursor: PlanCursor) extends AdvanceResult

/** The queue was empty — the plan has nothing left to run. */
final case class ScheduleComplete(cursor: PlanCursor) extends AdvanceResult

/** The head element was a [[PendingDecision]]. `cursorWithoutHead` already has it removed from
  * `remaining`; the caller is expected to resolve `pending` and feed the resulting legs back via
  * [[PlanCursor.expandPending]].
  */
final case class PendingResolutionRequired(pending: PendingDecision, cursorWithoutHead: PlanCursor)
    extends AdvanceResult

object PlanCursor {

  /** Looks at the head of `cursor.remaining` and either executes it, reports the plan is
    * complete, or surfaces the pending decision that needs resolving before progress can
    * continue.
    */
  def advance(cursor: PlanCursor): AdvanceResult = cursor.remaining.dequeue match {
    case None                          => ScheduleComplete(cursor)
    case Some((e: ExecutedElement, r)) => Advanced(cursor.copy(executed = cursor.executed :+ e, remaining = r))
    case Some((p: PendingDecision, r)) => PendingResolutionRequired(p, cursor.copy(remaining = r))
  }

  /** Splices the resolved legs in exactly where the [[PendingDecision]] used to sit. */
  def expandPending(pr: PendingResolutionRequired, resolved: List[AtomicLeg]): PlanCursor =
    pr.cursorWithoutHead.copy(remaining = pr.cursorWithoutHead.remaining.prepend(resolved))

  /** Discards the contiguous run of [[AtomicLeg]]s currently at the head of `cursor.remaining`
    * (stopping at the next non-leg element, typically an [[Activity]]) and replaces it with
    * `newLegs` — used when re-planning a trip that is already underway.
    */
  def expandReplan(cursor: PlanCursor, newLegs: List[AtomicLeg]): PlanCursor = {
    val (_, afterCurrentRun) = cursor.remaining.dropCurrentLegRun
    cursor.copy(remaining = afterCurrentRun.prepend(newLegs))
  }
}
