package org.interscity.htc
package model.hybrid.entity.state

import org.interscity.htc.model.hybrid.entity.state.enumeration.LaneTypeEnum

/** Microscopic state for a bicycle in MICRO simulation mode.
  * 
  * Bicycles have unique characteristics:
  * - Short vehicle length (2m)
  * - Low acceleration capability
  * - Lower speeds (15-25 km/h typically)
  * - Prefer bike lanes when available
  * - Can share lanes with cars if necessary
  * - Smaller safety gaps
  * - Vulnerable road user considerations
  * 
  * @param positionInLink Position from link start (meters)
  * @param velocity Current velocity (m/s)
  * @param acceleration Current acceleration (m/s²)
  * @param currentLane Current lane number (0-indexed)
  * @param leaderVehicle ID of the vehicle ahead
  * @param gapToLeader Gap to leader (meters)
  * @param leaderVelocity Velocity of the leader (m/s)
  * @param maxAcceleration Maximum acceleration (default: 1.0 m/s² - low)
  * @param maxDeceleration Maximum deceleration (default: 3.0 m/s²)
  * @param minGap Minimum safe gap (default: 1.5 m - smaller than car)
  * @param desiredVelocity Desired velocity (default: 5.56 m/s = 20 km/h)
  * @param reactionTime Cyclist reaction time (default: 1.2 s)
  * @param vehicleLength Bicycle length (default: 2.0 m)
  * @param prefersBikeLane Whether cyclist prefers bike lane
  * @param canUseSidewalk Whether allowed to use sidewalk (context-dependent)
  * @param desiredLane Target lane for lane change
  * @param laneChangeProgress Progress of lane change (0.0 to 1.0)
  */
case class MicroBicycleState(
  positionInLink: Double,
  velocity: Double,
  acceleration: Double,
  currentLane: Int,
  leaderVehicle: Option[String] = None,
  gapToLeader: Double = Double.MaxValue,
  leaderVelocity: Double = 0.0,
  
  // Bicycle-specific parameters
  maxAcceleration: Double = 1.0,      // m/s² (lower than car)
  maxDeceleration: Double = 3.0,      // m/s²
  minGap: Double = 1.5,               // m (smaller than car)
  desiredVelocity: Double = 5.56,     // m/s (20 km/h)
  reactionTime: Double = 1.2,         // s
  vehicleLength: Double = 2.0,        // m
  
  // Bicycle-specific behavior
  prefersBikeLane: Boolean = true,
  canUseSidewalk: Boolean = false,
  
  // Lane change
  desiredLane: Option[Int] = None,
  laneChangeProgress: Double = 0.0
) extends MicroMovableState {
  
  /** Create a copy with updated kinematics */
  def withUpdatedKinematics(
    newPosition: Double,
    newVelocity: Double,
    newAcceleration: Double
  ): MicroBicycleState = {
    this.copy(
      positionInLink = newPosition,
      velocity = newVelocity,
      acceleration = newAcceleration
    )
  }
  
  /** Create a copy with updated leader information */
  def withUpdatedLeader(
    leaderId: Option[String],
    gap: Double,
    leaderVel: Double
  ): MicroBicycleState = {
    this.copy(
      leaderVehicle = leaderId,
      gapToLeader = gap,
      leaderVelocity = leaderVel
    )
  }
  
  /** Create a copy with lane change initiated */
  def initiatingLaneChange(targetLane: Int): MicroBicycleState = {
    this.copy(
      desiredLane = Some(targetLane),
      laneChangeProgress = 0.0
    )
  }
  
  /** Check if in bike lane */
  def isInBikeLane(laneType: LaneTypeEnum): Boolean = {
    laneType == LaneTypeEnum.BIKE_LANE
  }
}

object MicroBicycleState {
  /** Create an initial micro state for a bicycle entering a MICRO link */
  def initial(
    lane: Int = 0,
    prefersBikeLane: Boolean = true,
    customDesiredVelocity: Option[Double] = None
  ): MicroBicycleState = {
    MicroBicycleState(
      positionInLink = 0.0,
      velocity = 0.0,
      acceleration = 0.0,
      currentLane = lane,
      prefersBikeLane = prefersBikeLane,
      desiredVelocity = customDesiredVelocity.getOrElse(5.56)
    )
  }
}
