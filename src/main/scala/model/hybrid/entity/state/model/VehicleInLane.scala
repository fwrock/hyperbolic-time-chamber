package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick

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
                          entryTick: Tick = 0,
                          microStateRef: Option[Any] = None  // Can hold MicroCarState, MicroBusState, etc.
                        ) {
  /** Calculate the rear position of the vehicle */
  def rearPosition: Double = position - vehicleLength

  /** Calculate gap to a leader vehicle */
  def gapTo(leader: VehicleInLane): Double = {
    leader.rearPosition - this.position
  }
}
