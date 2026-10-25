package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

case class MicroBoundaryVehicle(id: Long, position: Double, velocity: Double, length: Double)
case class MicroBoundarySnapshot(linkId: Long, tick: Tick, sequence: Long, length: Double,
                                 vehicle: Option[MicroBoundaryVehicle], supported: Boolean = true,
                                 stepSeconds: Double = 0.1)

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
  var observations: Map[Long, MicroBoundaryHistory] = Map.empty
)
