package org.interscity.htc
package model.hybrid.entity.state

/** Microscopic state for a car in MICRO simulation mode.
  * 
  * This state is activated when a car enters a link configured with MICRO mode.
  * It contains all the kinematic and perceptual information needed for
  * car-following models and lane-change models.
  * 
  * Default parameters are based on typical passenger car characteristics.
  * 
  * @param positionInLink Position from link start (meters)
  * @param velocity Current velocity (m/s)
  * @param acceleration Current acceleration (m/s²)
  * @param currentLane Current lane number (0-indexed)
  * @param leaderVehicle ID of the vehicle ahead
  * @param gapToLeader Gap to leader (meters, bumper-to-bumper)
  * @param leaderVelocity Velocity of the leader (m/s)
  * @param maxAcceleration Maximum acceleration (default: 2.6 m/s²)
  * @param maxDeceleration Maximum deceleration (default: 4.5 m/s²)
  * @param minGap Minimum safe gap (default: 2.0 m)
  * @param desiredVelocity Desired free-flow velocity (default: 13.89 m/s = 50 km/h)
  * @param reactionTime Driver reaction time (default: 1.0 s)
  * @param vehicleLength Length of the car (default: 4.5 m)
  * @param desiredLane Target lane for lane change
  * @param laneChangeProgress Progress of lane change (0.0 to 1.0)
  */
case class MicroCarState(
  positionInLink: Double,
  velocity: Double,
  acceleration: Double,
  currentLane: Int,
  leaderVehicle: Option[String] = None,
  gapToLeader: Double = Double.MaxValue,
  leaderVelocity: Double = 0.0,
  
  // Car-specific parameters (typical passenger car)
  maxAcceleration: Double = 2.6,      // m/s²
  maxDeceleration: Double = 4.5,      // m/s²
  minGap: Double = 2.0,               // m
  desiredVelocity: Double = 13.89,    // m/s (50 km/h)
  reactionTime: Double = 1.0,         // s
  vehicleLength: Double = 4.5,        // m
  
  // Lane change
  desiredLane: Option[Int] = None,
  laneChangeProgress: Double = 0.0
) extends MicroMovableState {
  
  /** Create a copy with updated kinematics after a simulation step */
  def withUpdatedKinematics(
    newPosition: Double,
    newVelocity: Double,
    newAcceleration: Double
  ): MicroCarState = {
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
  ): MicroCarState = {
    this.copy(
      leaderVehicle = leaderId,
      gapToLeader = gap,
      leaderVelocity = leaderVel
    )
  }
  
  /** Create a copy with lane change initiated */
  def initiatingLaneChange(targetLane: Int): MicroCarState = {
    this.copy(
      desiredLane = Some(targetLane),
      laneChangeProgress = 0.0
    )
  }
  
  /** Create a copy with lane change progress updated */
  def progressingLaneChange(progress: Double): MicroCarState = {
    if (progress >= 1.0) {
      // Lane change complete
      this.copy(
        currentLane = desiredLane.getOrElse(currentLane),
        desiredLane = None,
        laneChangeProgress = 0.0
      )
    } else {
      this.copy(laneChangeProgress = progress)
    }
  }
}

object MicroCarState {
  /** Create an initial micro state for a car entering a MICRO link */
  def initial(
    lane: Int = 0,
    initialVelocity: Double = 0.0,
    customDesiredVelocity: Option[Double] = None
  ): MicroCarState = {
    MicroCarState(
      positionInLink = 0.0,
      velocity = initialVelocity,
      acceleration = 0.0,
      currentLane = lane,
      desiredVelocity = customDesiredVelocity.getOrElse(13.89)
    )
  }
}
