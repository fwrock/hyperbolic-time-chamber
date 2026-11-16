package org.interscity.htc
package core.partial

import scala.collection.mutable

/** Builds both directions of a composition graph, retaining input diagnostics for the caller.
  *
  * Invalid links are reported and omitted. Valid links are deduplicated before traversal; all
  * mutable indexes belong to one closure calculation and are never shared across runs.
  */
private[partial] final class PartialCompositionGraph(
  allActors: collection.Set[Long],
  links: Iterable[(Long, Long)]
) {
  private val invalidLinks = mutable.ListBuffer.empty[String]
  private val wholesOf = mutable.Map.empty[Long, mutable.Set[Long]]
  private val partsOf = mutable.Map.empty[Long, mutable.Set[Long]]

  links.foreach {
    case (part, whole) => addLink(part, whole)
  }

  /** Returns the wholes that must be retained when this part is kept. */
  def wholes(part: Long): collection.Set[Long] = wholesOf.getOrElse(part, Set.empty[Long])

  /** Returns the parts reached by descending from a selected whole. */
  def parts(whole: Long): collection.Set[Long] = partsOf.getOrElse(whole, Set.empty[Long])

  /** Reports invalid links in input order, followed by cycle diagnostics from iterative DFS. */
  def problems: List[String] = invalidLinks.toList ++ cycles()

  /** Rejects invalid endpoints before adding the same relation to both directional indexes. */
  private def addLink(part: Long, whole: Long): Unit =
    if (!allActors.contains(part))
      invalidLinks += s"relation mentions actor $part, which does not exist"
    else if (!allActors.contains(whole))
      invalidLinks += s"actor $part is part of $whole, which does not exist"
    else if (part == whole) invalidLinks += s"actor $part is declared a part of itself"
    else {
      wholesOf.getOrElseUpdate(part, mutable.Set.empty) += whole
      partsOf.getOrElseUpdate(whole, mutable.Set.empty) += part
    }

  /** Distinguishes an active DFS path from a completed subtree without runtime magic numbers. */
  private enum VisitState {
    case Visiting, Complete
  }

  /** Detects back edges without recursion so deep scenario hierarchies do not exhaust the stack. */
  private def cycles(): List[String] = {
    val state = mutable.Map.empty[Long, VisitState]
    val found = mutable.ListBuffer.empty[String]

    /** Finishes one DFS tree while retaining completed nodes for subsequent roots. */
    def visit(start: Long): Unit = {
      val stack = mutable.Stack[(Long, Iterator[Long])]()
      state(start) = VisitState.Visiting
      stack.push((start, wholes(start).iterator))
      while (stack.nonEmpty) {
        val (node, remaining) = stack.top
        if (remaining.hasNext) {
          val next = remaining.next()
          state.get(next) match {
            case None =>
              state(next) = VisitState.Visiting
              stack.push((next, wholes(next).iterator))
            case Some(VisitState.Visiting) =>
              found += s"cycle of part_of relations through actors $node and $next"
            case Some(VisitState.Complete) => ()
          }
        } else {
          state(node) = VisitState.Complete
          stack.pop()
        }
      }
    }

    wholesOf.keys.toList.sorted.foreach {
      node =>
        if (!state.contains(node)) visit(node)
    }
    found.toList
  }
}
