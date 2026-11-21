package org.interscity.htc
package model.mobility.entity.state.model

import core.types.Tick
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

case class MicroBoundaryVehicle(id: Long, position: Double, velocity: Double, length: Double,
                                nextLinkId: Long = 0L, pendingExit: Boolean = false)
case class MicroBoundarySnapshot(linkId: Long, tick: Tick, sequence: Long, length: Double,
                                 vehicle: Option[MicroBoundaryVehicle], supported: Boolean = true,
                                 stepSeconds: Double = 0.1, laneCount: Int = 1,
                                 @JsonDeserialize(contentAs = classOf[MicroLaneBoundary])
                                 laneBoundaries: List[MicroLaneBoundary] = Nil) {
  require(laneCount > 0, "invalid MICRO boundary lane count")
  require(laneBoundaries.isEmpty || laneBoundaries.map(_.laneId).sorted == (0 until laneCount).toList,
    "MICRO boundary requires exactly one observation per lane")
}

case class MicroBoundaryHistory(var snapshots: List[MicroBoundarySnapshot] = Nil) {
  def accept(snapshot: MicroBoundarySnapshot): Unit = {
    // Keep both tick cohorts: current-tick publications cannot replace the start-of-step view.
    snapshots = (snapshot :: snapshots).groupBy(_.tick).valuesIterator
      .map(_.maxBy(_.sequence)).toList.sortBy(_.tick)(Ordering.Long.reverse).take(2)
  }
  def before(tick: Tick): Option[MicroBoundarySnapshot] = snapshots.find(_.tick < tick)
}

case class MicroFollowingState(
  var subscribers: List[Long] = Nil,
  var requested: List[Long] = Nil,
  var sequence: Long = 0L,
  @JsonDeserialize(keyAs = classOf[java.lang.Long], contentAs = classOf[MicroBoundaryHistory])
  var observations: Map[Long, MicroBoundaryHistory] = Map.empty,
  @JsonDeserialize(contentAs = classOf[MicroBoundarySubscriber])
  var otherSubscribers: List[MicroBoundarySubscriber] = Nil
)
