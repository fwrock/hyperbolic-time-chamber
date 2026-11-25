package org.interscity.htc
package model.mobility.entity.state.model

/** Lanes that lead to each outgoing link, built once from immutable connections so per-vehicle
  * lookups avoid rescanning the connection list. Order and duplicates match
  * `connections.filter(_.outgoingLinkId == next).map(_.fromLane)`.
  */
final class LaneConnectionIndex(connections: List[LaneConnection]) {
  private val byNext: Map[Long, Vector[Int]] =
    connections.groupBy(_.outgoingLinkId).map { case (next, cs) => next -> cs.map(_.fromLane).toVector }
  private val sets = scala.collection.mutable.Map.empty[Long, Set[Int]]

  def legal(next: Long): Vector[Int] = byNext.getOrElse(next, Vector.empty)

  def legalSet(next: Long): Set[Int] = sets.getOrElseUpdate(next, legal(next).toSet)
}

object LaneConnectionIndex {
  def apply(connections: List[LaneConnection]): LaneConnectionIndex = new LaneConnectionIndex(connections)
}
