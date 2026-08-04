package org.interscity.htc
package model.hybrid.entity.state.plan

import com.fasterxml.jackson.annotation.{ JsonCreator, JsonValue }

/** The not-yet-executed tail of a person's plan.
  *
  * `RemainingQueue` deliberately exposes no way to append/enqueue at the tail — `prepend` only
  * ever inserts at the head. That asymmetry is intentional, not an oversight: it is what makes it
  * impossible to splice a mode-choice expansion in anywhere except exactly where the
  * [[PendingDecision]]/replanned run it replaces used to be, preventing out-of-order insertion.
  * Do not add an `append`/`enqueue` method even though one may look "missing" for API symmetry.
  *
  * `@JsonValue`/`@JsonCreator` make this serialize as a plain JSON array of [[PlanElement]]s
  * (rather than an object wrapping a private field, which Jackson's default bean introspection
  * would otherwise silently serialize as empty — `underlying` has no public accessor by design).
  * `PersonState` is deserialized generically by `core.util.JsonUtil`/`core.actor.BaseActor`, so
  * this needs to round-trip correctly with no per-actor custom decoder to fall back on.
  */
final case class RemainingQueue private (private val underlying: List[PlanElement]) {

  def dequeue: Option[(PlanElement, RemainingQueue)] = underlying match {
    case head :: tail => Some((head, RemainingQueue(tail)))
    case Nil          => None
  }

  /** Inserts `newHead` at the front of the queue, ahead of whatever remains. */
  def prepend(newHead: List[PlanElement]): RemainingQueue = RemainingQueue(newHead ++ underlying)

  /** Splits off the contiguous run of [[AtomicLeg]]s at the head of the queue, stopping at the
    * first element that is not an `AtomicLeg` (typically the next [[Activity]]), which is left
    * untouched in the remainder.
    */
  def dropCurrentLegRun: (List[AtomicLeg], RemainingQueue) = {
    val (run, rest) = underlying.span(_.isInstanceOf[AtomicLeg])
    (run.asInstanceOf[List[AtomicLeg]], RemainingQueue(rest))
  }

  def isEmpty: Boolean = underlying.isEmpty

  @JsonValue
  private def asJsonArray: List[PlanElement] = underlying
}

object RemainingQueue {
  @JsonCreator
  def apply(elements: List[PlanElement]): RemainingQueue = new RemainingQueue(elements)
}
