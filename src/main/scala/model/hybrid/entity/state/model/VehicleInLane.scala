package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick

import scala.collection.mutable

/** Represents a vehicle currently in a lane during microscopic simulation.
  *
  * @param actorId
  *   ID of the vehicle actor
  * @param shardId
  *   Shard ID for distributed actors
  * @param position
  *   Position in link (meters from start)
  * @param velocity
  *   Current velocity (m/s)
  * @param acceleration
  *   Current acceleration (m/s²)
  * @param vehicleLength
  *   Length of the vehicle (m)
  * @param microStateRef
  *   Reference to the full micro state of the vehicle
  */
case class VehicleInLane(
  actorId: Long,
  shardId: String,
  position: Double,
  velocity: Double,
  acceleration: Double,
  vehicleLength: Double,
  entryTick: Tick = 0,
  maxAcceleration: Double = 2.6,
  maxDeceleration: Double = 4.5,
  microStateRef: Option[Any] = None, // Can hold MicroCarState, MicroBusState, etc.
  exitSpeedLimitMps: Option[Double] = None,
  nextLinkId: Long = 0L,
  pendingExitTick: Option[Tick] = None,
  entryPosition: Double = 0.0,
  arrivalPosition: Option[Double] = None,
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs = classOf[java.lang.Double])
  lastLaneChangeSeconds: Option[Double] = None,
  minGap: Double = 2.0,
  reactionTime: Double = 1.0,
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs = classOf[java.lang.Double])
  maxSpeed: Option[Double] = None,
  speedFactor: Double = 1.0,
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs = classOf[java.lang.Double])
  desiredMaxSpeed: Option[Double] = None,
  sigma: Double = 0.0,
  randomSeed: Long = 0L,
  lc2013: Lc2013Parameters = Lc2013Parameters(),
  lc2013Memory: Lc2013Memory = Lc2013Memory()
) {

  /** Calculate the rear position of the vehicle */
  def rearPosition: Double = position - vehicleLength

  def desiredSpeed(roadSpeedMps: Double): Double = MicroSpeed.limit(roadSpeedMps,maxSpeed,speedFactor,desiredMaxSpeed)

  /** Calculate gap to a leader vehicle */
  def gapTo(leader: VehicleInLane): Double =
    leader.rearPosition - this.position
}

object VehicleInLane {
  /** Stable front-to-back ordering. Equal positions use the logical vehicle ID, never arrival order. */
  def precedes(left: VehicleInLane, right: VehicleInLane): Boolean =
    left.position > right.position ||
      (left.position == right.position && left.actorId < right.actorId)

  def insertFrontToBack(queue: mutable.Queue[VehicleInLane], vehicle: VehicleInLane): Unit = {
    val index = queue.indexWhere(existing => precedes(vehicle, existing))
    if (index >= 0) queue.insert(index, vehicle)
    else queue.enqueue(vehicle)
  }

  def sortFrontToBack(queue: mutable.Queue[VehicleInLane]): Unit = {
    var sorted = true
    var i = 1
    while (sorted && i < queue.length) {
      if (precedes(queue(i), queue(i - 1))) sorted = false
      i += 1
    }
    if (!sorted) {
      val ordered = queue.sortWith(precedes)
      queue.clear()
      queue.enqueueAll(ordered)
    }
  }
}
