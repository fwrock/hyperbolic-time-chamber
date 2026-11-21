package org.interscity.htc
package model.mobility.micro.strategy

import model.mobility.entity.state.model.VehicleInLane
import scala.collection.mutable

/** Nearest vehicles ahead of and behind a position in one lane.
  *
  * `linear` is order-independent and defines the result. `sorted` returns the same result by binary
  * search when the queue is in `VehicleInLane.precedes` order (position descending, ID ascending).
  * Ties at equal position go to the lower ID ahead and, behind, also the lower ID.
  */
object LaneNeighbours {
  type Pair = (Option[VehicleInLane], Option[VehicleInLane])

  def isSorted(queue: mutable.Queue[VehicleInLane]): Boolean = {
    var i = 1
    while (i < queue.length) {
      if (VehicleInLane.precedes(queue(i), queue(i - 1))) return false
      i += 1
    }
    true
  }

  def linear(queue: mutable.Queue[VehicleInLane], vehicle: VehicleInLane): Pair = {
    var ahead: Option[VehicleInLane] = None
    var behind: Option[VehicleInLane] = None
    queue.foreach { v =>
      if (v.actorId != vehicle.actorId) {
        if (v.position >= vehicle.position) {
          if (ahead.forall(a => v.position < a.position || (v.position == a.position && v.actorId < a.actorId))) ahead = Some(v)
        } else if (behind.forall(b => v.position > b.position || (v.position == b.position && v.actorId < b.actorId)))
          behind = Some(v)
      }
    }
    (ahead, behind)
  }

  /** First index in [0, n) whose position is below `p` (the queue is position-descending). */
  private def firstBelow(queue: mutable.Queue[VehicleInLane], p: Double): Int = {
    var lo = 0
    var hi = queue.length
    while (lo < hi) {
      val mid = (lo + hi) >>> 1
      if (queue(mid).position >= p) lo = mid + 1 else hi = mid
    }
    lo
  }

  /** First index of the run of vehicles sharing the position at `last` (ids ascend within it). */
  private def runStart(queue: mutable.Queue[VehicleInLane], last: Int): Int = {
    val p = queue(last).position
    var lo = 0
    var hi = last
    while (lo < hi) {
      val mid = (lo + hi) >>> 1
      if (queue(mid).position > p) lo = mid + 1 else hi = mid
    }
    lo
  }

  def sorted(queue: mutable.Queue[VehicleInLane], vehicle: VehicleInLane): Pair = {
    val k = firstBelow(queue, vehicle.position)
    var ahead: Option[VehicleInLane] = None
    var end = k
    while (ahead.isEmpty && end > 0) {
      val start = runStart(queue, end - 1)
      var i = start
      while (ahead.isEmpty && i < end) {
        if (queue(i).actorId != vehicle.actorId) ahead = Some(queue(i))
        i += 1
      }
      end = start
    }
    var behind: Option[VehicleInLane] = None
    var j = k
    while (behind.isEmpty && j < queue.length) {
      if (queue(j).actorId != vehicle.actorId) behind = Some(queue(j))
      j += 1
    }
    (ahead, behind)
  }
}
