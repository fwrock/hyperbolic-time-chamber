package org.interscity.htc
package model.hybrid.micro.strategy

import model.hybrid.entity.state.model.VehicleInLane

import scala.collection.mutable

/**
 * Strategy interface for lane-change decision making in microscopic simulation.
 * 
 * Implementations define when and how vehicles should change lanes, considering
 * factors like gap acceptance, incentive to change lanes (MOBIL model), and
 * safety constraints.
 * 
 * This interface is provided for future extensibility. Currently, the system
 * does not implement active lane changing during simulation.
 * 
 * @see model.hybrid.micro.lane.MobilLaneChange for future MOBIL implementation
 */
trait LaneChangeStrategy {
  
  /**
   * Determines if a vehicle should change lanes and to which lane.
   * 
   * @param vehicleId ID of the vehicle considering lane change
   * @param currentLane Current lane of the vehicle
   * @param currentVelocity Current velocity of the vehicle in m/s
   * @param vehiclesByLane State of all lanes
   * @param linkLength Total link length in meters
   * @param speedLimit Speed limit in km/h
   * @return Optional target lane ID if lane change is desired, None otherwise
   */
  def shouldChangeLane(
    vehicleId: String,
    currentLane: Int,
    currentVelocity: Double,
    vehiclesByLane: mutable.Map[Int, mutable.Queue[VehicleInLane]],
    linkLength: Double,
    speedLimit: Double
  ): Option[Int]
  
  /**
   * Checks if a lane change is safe (sufficient gap in target lane).
   * 
   * @param vehicle Vehicle attempting lane change
   * @param targetLane Target lane ID
   * @param vehiclesByLane State of all lanes
   * @return true if lane change is safe, false otherwise
   */
  def isSafeLaneChange(
    vehicle: VehicleInLane,
    targetLane: Int,
    vehiclesByLane: mutable.Map[Int, mutable.Queue[VehicleInLane]]
  ): Boolean = {
    vehiclesByLane.get(targetLane) match {
      case Some(queue) =>
        val ahead = queue.find(_.position > vehicle.position)
        val behind = queue.reverse.find(_.position < vehicle.position)
        
        val gapAhead = ahead match {
          case Some(v) => v.position - vehicle.position - vehicle.vehicleLength
          case None => Double.MaxValue
        }
        
        val gapBehind = behind match {
          case Some(v) => vehicle.position - v.position - v.vehicleLength
          case None => Double.MaxValue
        }
        
        gapAhead > 5.0 && gapBehind > 5.0
        
      case None => false
    }
  }
  
  /**
   * Initializes the strategy with link-specific parameters.
   * 
   * @param linkLength Length of the link in meters
   * @param speedLimit Speed limit in km/h
   * @param lanes Number of lanes
   */
  def initialize(linkLength: Double, speedLimit: Double, lanes: Int): Unit = {}
}

/**
 * No-op lane change strategy that never initiates lane changes.
 * 
 * This is the default strategy used when lane changing is disabled.
 */
class NoLaneChangeStrategy extends LaneChangeStrategy {
  override def shouldChangeLane(
    vehicleId: String,
    currentLane: Int,
    currentVelocity: Double,
    vehiclesByLane: mutable.Map[Int, mutable.Queue[VehicleInLane]],
    linkLength: Double,
    speedLimit: Double
  ): Option[Int] = None // Never change lanes
}

object NoLaneChangeStrategy {
  def apply(): NoLaneChangeStrategy = new NoLaneChangeStrategy()
}
