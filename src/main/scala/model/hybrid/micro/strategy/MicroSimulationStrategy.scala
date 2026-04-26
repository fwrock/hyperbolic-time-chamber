package org.interscity.htc
package model.hybrid.micro.strategy

import core.types.Tick
import model.hybrid.entity.state.model.VehicleInLane

import scala.collection.mutable

/**
 * Strategy interface for microscopic traffic simulation algorithms.
 * 
 * Implementations of this interface define how vehicles move and interact
 * within a microscopic link during sub-tick execution. Different strategies
 * can implement various car-following models, lane-change behaviors, and
 * traffic flow dynamics.
 * 
 * @see DefaultMicroSimulationStrategy for the default Krauss-based implementation
 */
trait MicroSimulationStrategy {
  
  /**
   * Executes a single sub-tick of microscopic simulation for all lanes.
   * 
   * @param vehiclesByLane Map of lane ID to queue of vehicles in that lane
   * @param subTick Current sub-tick index (0 to microTicksPerGlobalTick-1)
   * @param tick Current global tick
   * @param linkLength Total length of the link in meters
   * @param speedLimit Speed limit of the link in km/h
   * @param microTimeStep Duration of each sub-tick in seconds
   * @param microTicksPerGlobalTick Number of sub-ticks per global tick
   * @param vehicleWaitingSeconds Map tracking accumulated waiting time per vehicle
   * @return Sequence of updates to send to vehicles
   */
  def executeSubTick(
    vehiclesByLane: mutable.Map[Int, mutable.Queue[VehicleInLane]],
    subTick: Int,
    tick: Tick,
    linkLength: Double,
    speedLimit: Double,
    microTimeStep: Double,
    microTicksPerGlobalTick: Int,
    vehicleWaitingSeconds: mutable.Map[String, Double]
  ): Seq[MicroVehicleUpdate]
  
  /**
   * Initializes the strategy with link-specific parameters.
   * Called once when the link enters MICRO mode.
   * 
   * @param linkLength Length of the link in meters
   * @param speedLimit Speed limit in km/h
   * @param lanes Number of lanes
   * @param microTimeStep Duration of each sub-tick in seconds
   */
  def initialize(
    linkLength: Double,
    speedLimit: Double,
    lanes: Int,
    microTimeStep: Double
  ): Unit = {}
  
  /**
   * Determines the best lane for a vehicle entering the link.
   * 
   * @param vehiclesByLane Current state of all lanes
   * @param vehicleId ID of the entering vehicle
   * @param vehicleLength Length of the vehicle in meters
   * @return Lane ID (0-indexed) for the vehicle to enter
   */
  def selectEntryLane(
    vehiclesByLane: mutable.Map[Int, mutable.Queue[VehicleInLane]],
    vehicleId: String,
    vehicleLength: Double
  ): Int = {
    if (vehiclesByLane.isEmpty) 0
    else vehiclesByLane.minBy(_._2.size)._1
  }
}

/**
 * Container for micro-simulation update data to be sent to a vehicle.
 * 
 * @param vehicleId ID of the vehicle to update
 * @param shardId Shard ID where the vehicle actor resides
 * @param subTick Sub-tick index when this update was generated
 * @param position Current position in the link (meters from start)
 * @param velocity Current velocity in m/s
 * @param acceleration Current acceleration in m/s²
 * @param currentLane Current lane ID
 * @param leaderVehicle Optional ID of the vehicle ahead
 * @param gapToLeader Gap to leader vehicle in meters
 * @param leaderVelocity Leader vehicle velocity in m/s
 * @param safeVelocity Safe velocity calculated by car-following model
 */
case class MicroVehicleUpdate(
  vehicleId: String,
  shardId: String,
  subTick: Int,
  position: Double,
  velocity: Double,
  acceleration: Double,
  currentLane: Int,
  leaderVehicle: Option[String],
  gapToLeader: Double,
  leaderVelocity: Double,
  safeVelocity: Double,
  reachedEnd: Boolean = false
)
