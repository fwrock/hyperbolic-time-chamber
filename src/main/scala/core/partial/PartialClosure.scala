package org.interscity.htc
package core.partial

import core.entity.actor.{ ActorSimulation, ShardActorId }

import scala.collection.mutable

/** Computes partial-simulation retention from selected units and composition relations.
  *
  * Explicitly selected actors retain all their transitive parts. Every retained part also retains
  * its transitive wholes, but those inferred wholes do not bring their other parts. Unselected
  * actors outside this closure are omitted. See simedape/docs/PARTIAL_SIMULATION.md for the input
  * contract. Reference-only relationships do not affect the closure; infrastructure filtering is
  * the caller's responsibility. This calculation performs no I/O or actor interactions.
  */
object PartialClosure {

  /** Closed retention reasons, kept nested to preserve their existing JVM identities. */
  sealed trait Reason

  /** The actor was explicitly selected. */
  case object Selected extends Reason

  /** The actor is a transitive part of an explicitly selected whole. */
  case object PartOfSelectedWhole extends Reason

  /** The actor is a transitive whole of an already retained part. */
  case object WholeOfKeptPart extends Reason

  /** Retention snapshot and diagnostics; callers must reject input when problems is nonempty.
    *
    * @param kept
    *   ids to instantiate
    * @param reasons
    *   why each kept id is kept
    * @param problems
    *   input inconsistencies (dangling relation, cycle); the load must fail if non-empty
    * @param selectedNotFound
    *   selected ids that do not exist in the scenario
    */
  final case class Result(
    kept: Set[Long],
    reasons: Map[Long, Reason],
    problems: List[String],
    selectedNotFound: Set[Long]
  ) {

    /** Tests membership in the computed closure without traversing relations again. */
    def isKept(id: Long): Boolean = kept.contains(id)
  }

  /** `(part, whole)` pairs declared by one actor, from its `part_of` and `contains` entries. */
  def linksOf(actor: ActorSimulation): Seq[(Long, Long)] =
    Option(actor.relationships).toSeq.flatMap(_.values).flatMap {
      rel =>
        rel.relation match {
          case ShardActorId.PartOf   => Some((actor.id, rel.entityId))
          case ShardActorId.Contains => Some((rel.entityId, actor.id))
          case _                     => None
        }
    }

  /** Computes retention in two passes: down from explicit selections, then up from all kept parts.
    *
    * Upward retention never triggers another downward pass, so keeping an owner does not bring its
    * other parts. Explicit selection takes precedence over inferred reasons. Input inconsistencies
    * are returned in problems; the caller must reject the load when any are present.
    */
  def compute(
    allActors: collection.Set[Long],
    links: Iterable[(Long, Long)],
    selected: Set[Long]
  ): Result = {
    val graph = new PartialCompositionGraph(allActors, links)
    val problems = graph.problems
    val present = selected.filter(allActors.contains)
    val reasons = mutable.Map.empty[Long, Reason]
    present.foreach(reasons(_) = Selected)

    keepReachable(present, graph.parts, PartOfSelectedWhole, reasons)
    keepReachable(reasons.keys, graph.wholes, WholeOfKeptPart, reasons)
    Result(reasons.keySet.toSet, reasons.toMap, problems, selected -- present)
  }

  /** Adds each reachable actor once, preserving any reason assigned by an earlier pass.
    *
    * Seeds are copied before traversal because the upward pass starts from the keys of the map it
    * extends. Queue traversal also terminates on cyclic input while diagnostics remain available.
    */
  private def keepReachable(
    seeds: Iterable[Long],
    neighbors: Long => collection.Set[Long],
    reason: Reason,
    reasons: mutable.Map[Long, Reason]
  ): Unit = {
    val pending = mutable.Queue.from(seeds)
    while (pending.nonEmpty)
      neighbors(pending.dequeue()).foreach {
        id =>
          if (!reasons.contains(id)) {
            reasons(id) = reason
            pending.enqueue(id)
          }
      }
  }
}
