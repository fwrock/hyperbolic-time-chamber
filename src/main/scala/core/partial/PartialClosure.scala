package org.interscity.htc
package core.partial

import core.entity.actor.{ ActorSimulation, ShardActorId }

import scala.collection.mutable

/** Which actors a partial simulation keeps, given the selected units and the composition relations
  * (`part_of` / `contains`) declared in the scenario input. Pure logic: no I/O, no actors; the
  * loaders only ask "keep this id?".
  *
  * Rules (see `simedape/docs/PARTIAL_SIMULATION.md`):
  *   1. every selected actor is kept;
  *   2. a selected whole keeps all its parts, transitively (selecting a person keeps their cars);
  *   3. any kept part keeps all its wholes, transitively; a whole kept this way does NOT bring its
  *      other parts;
  *   4. a whole that is neither selected nor has a kept part is not kept.
  *
  * Actors that are only referenced (`reference`) take no part in this: infrastructure is the
  * caller's business, it is never filtered here.
  */
object PartialClosure {

  sealed trait Reason
  case object Selected extends Reason
  case object PartOfSelectedWhole extends Reason
  case object WholeOfKeptPart extends Reason

  /** @param kept ids to instantiate
    * @param reasons why each kept id is kept
    * @param problems input inconsistencies (dangling relation, cycle); the load must fail if non-empty
    * @param selectedNotFound selected ids that do not exist in the scenario
    */
  final case class Result(
    kept: Set[Long],
    reasons: Map[Long, Reason],
    problems: List[String],
    selectedNotFound: Set[Long]
  ) {
    def isKept(id: Long): Boolean = kept.contains(id)
  }

  /** `(part, whole)` pairs declared by one actor, from its `part_of` and `contains` entries. */
  def linksOf(actor: ActorSimulation): Seq[(Long, Long)] =
    Option(actor.relationships).toSeq.flatMap(_.values).flatMap { rel =>
      rel.relation match {
        case ShardActorId.PartOf   => Some((actor.id, rel.entityId))
        case ShardActorId.Contains => Some((rel.entityId, actor.id))
        case _                     => None
      }
    }

  def compute(allActors: collection.Set[Long], links: Iterable[(Long, Long)], selected: Set[Long]): Result = {
    val problems = mutable.ListBuffer.empty[String]
    val wholesOf = mutable.Map.empty[Long, mutable.Set[Long]]
    val partsOf = mutable.Map.empty[Long, mutable.Set[Long]]
    links.foreach { case (part, whole) =>
      if (!allActors.contains(part)) problems += s"relation mentions actor $part, which does not exist"
      else if (!allActors.contains(whole)) problems += s"actor $part is part of $whole, which does not exist"
      else if (part == whole) problems += s"actor $part is declared a part of itself"
      else {
        wholesOf.getOrElseUpdate(part, mutable.Set.empty) += whole
        partsOf.getOrElseUpdate(whole, mutable.Set.empty) += part
      }
    }
    problems ++= cycles(wholesOf)

    val present = selected.filter(allActors.contains)
    val reasons = mutable.Map.empty[Long, Reason]
    present.foreach(reasons(_) = Selected)

    // rule 2: from every selected whole, down through its parts
    val down = mutable.Queue.from(present)
    while (down.nonEmpty) {
      val whole = down.dequeue()
      partsOf.getOrElse(whole, Set.empty[Long]).foreach { part =>
        if (!reasons.contains(part)) { reasons(part) = PartOfSelectedWhole; down.enqueue(part) }
      }
    }
    // rule 3: from every kept actor, up through its wholes (does not go down again)
    val up = mutable.Queue.from(reasons.keys)
    while (up.nonEmpty) {
      val part = up.dequeue()
      wholesOf.getOrElse(part, Set.empty[Long]).foreach { whole =>
        if (!reasons.contains(whole)) { reasons(whole) = WholeOfKeptPart; up.enqueue(whole) }
      }
    }
    Result(reasons.keySet.toSet, reasons.toMap, problems.toList, selected -- present)
  }

  /** Cycles of `part_of` (an actor that is, through its wholes, part of itself). */
  private def cycles(wholesOf: mutable.Map[Long, mutable.Set[Long]]): List[String] = {
    val state = mutable.Map.empty[Long, Int] // 1 = on the current path, 2 = done
    val found = mutable.ListBuffer.empty[String]
    def visit(start: Long): Unit = {
      // iterative DFS: scenarios can have deep chains
      val stack = mutable.Stack[(Long, Iterator[Long])]()
      state(start) = 1
      stack.push((start, wholesOf.getOrElse(start, Set.empty[Long]).iterator))
      while (stack.nonEmpty) {
        val (node, it) = stack.top
        if (it.hasNext) {
          val next = it.next()
          state.getOrElse(next, 0) match {
            case 0 =>
              state(next) = 1
              stack.push((next, wholesOf.getOrElse(next, Set.empty[Long]).iterator))
            case 1 => found += s"cycle of part_of relations through actors $node and $next"
            case _ =>
          }
        } else {
          state(node) = 2
          stack.pop()
        }
      }
    }
    wholesOf.keys.toList.sorted.foreach(k => if (!state.contains(k)) visit(k))
    found.toList
  }
}
