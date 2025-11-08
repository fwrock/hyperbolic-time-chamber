package org.interscity.htc
package model.hybrid.entity.state.model

import org.interscity.htc.model.hybrid.entity.state.enumeration.LaneTypeEnum

/** Configuration for a single lane in a microscopic link.
  * 
  * @param laneId Unique identifier for the lane within the link (0-indexed from left)
  * @param laneType Type of lane (NORMAL, BUS_LANE, BIKE_LANE, etc.)
  * @param speedLimit Optional speed limit specific to this lane (m/s)
  * @param width Lane width in meters (default 3.5m for normal lanes)
  */
case class LaneConfig(
  laneId: Int,
  laneType: LaneTypeEnum = LaneTypeEnum.NORMAL,
  speedLimit: Option[Double] = None,
  width: Double = 3.5
)

/** Represents a vehicle currently in a lane during microscopic simulation.
  * 
  * @param actorId ID of the vehicle actor
  * @param shardId Shard ID for distributed actors
  * @param position Position in link (meters from start)
  * @param velocity Current velocity (m/s)
  * @param acceleration Current acceleration (m/s²)
  * @param vehicleLength Length of the vehicle (m)
  * @param microStateRef Reference to the full micro state of the vehicle
  */
case class VehicleInLane(
  actorId: String,
  shardId: String,
  position: Double,
  velocity: Double,
  acceleration: Double,
  vehicleLength: Double,
  microStateRef: Option[Any] = None  // Can hold MicroCarState, MicroBusState, etc.
) {
  /** Calculate the rear position of the vehicle */
  def rearPosition: Double = position - vehicleLength
  
  /** Calculate gap to a leader vehicle */
  def gapTo(leader: VehicleInLane): Double = {
    leader.rearPosition - this.position
  }
}
