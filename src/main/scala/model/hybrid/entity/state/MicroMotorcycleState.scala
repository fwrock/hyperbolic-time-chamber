package org.interscity.htc
package model.hybrid.entity.state

/** Microscopic state for a motorcycle in MICRO simulation mode.
  * 
  * Motorcycles have unique characteristics:
  * - Small vehicle length (2.5m)
  * - HIGH acceleration capability (better than cars)
  * - Can filter between lanes (lane splitting)
  * - Smaller gaps accepted (more aggressive)
  * - Faster acceleration/deceleration
  * - Flexible positioning in traffic
  * - Higher desired velocity
  * 
  * @param positionInLink Position from link start (meters)
  * @param velocity Current velocity (m/s)
  * @param acceleration Current acceleration (m/s²)
  * @param currentLane Current lane number (0-indexed)
  * @param leaderVehicle ID of the vehicle ahead
  * @param gapToLeader Gap to leader (meters)
  * @param leaderVelocity Velocity of the leader (m/s)
  * @param maxAcceleration Maximum acceleration (default: 3.5 m/s² - HIGHER than car)
  * @param maxDeceleration Maximum deceleration (default: 5.0 m/s²)
  * @param minGap Minimum safe gap (default: 1.5 m - smaller than car)
  * @param desiredVelocity Desired velocity (default: 16.67 m/s = 60 km/h)
  * @param reactionTime Rider reaction time (default: 0.9 s - faster than car)
  * @param vehicleLength Motorcycle length (default: 2.5 m)
  * @param canFilterLanes Whether lane splitting/filtering is allowed
  * @param aggressiveness Aggressiveness factor [0-1] affecting gap acceptance
  * @param desiredLane Target lane for lane change
  * @param laneChangeProgress Progress of lane change (0.0 to 1.0)
  * @param filteringBetweenLanes Currently filtering between two lanes
  */
case class MicroMotorcycleState(
  positionInLink: Double,
  velocity: Double,
  acceleration: Double,
  currentLane: Int,
  leaderVehicle: Option[String] = None,
  gapToLeader: Double = Double.MaxValue,
  leaderVelocity: Double = 0.0,
  
  // Motorcycle-specific parameters
  maxAcceleration: Double = 3.5,      // m/s² (HIGHER than car!)
  maxDeceleration: Double = 5.0,      // m/s²
  minGap: Double = 1.5,               // m (smaller than car)
  desiredVelocity: Double = 16.67,    // m/s (60 km/h)
  reactionTime: Double = 0.9,         // s (faster than car)
  vehicleLength: Double = 2.5,        // m
  
  // Motorcycle-specific behavior
  canFilterLanes: Boolean = true,     // Lane splitting capability
  aggressiveness: Double = 0.7,       // [0-1] affects gap acceptance
  
  // Lane change (more aggressive)
  desiredLane: Option[Int] = None,
  laneChangeProgress: Double = 0.0,
  filteringBetweenLanes: Boolean = false
) extends MicroMovableState {
  
  /** Create a copy with updated kinematics */
  def withUpdatedKinematics(
    newPosition: Double,
    newVelocity: Double,
    newAcceleration: Double
  ): MicroMotorcycleState = {
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
  ): MicroMotorcycleState = {
    this.copy(
      leaderVehicle = leaderId,
      gapToLeader = gap,
      leaderVelocity = leaderVel
    )
  }
  
  /** Create a copy with lane change initiated */
  def initiatingLaneChange(targetLane: Int): MicroMotorcycleState = {
    this.copy(
      desiredLane = Some(targetLane),
      laneChangeProgress = 0.0
    )
  }
  
  /** Create a copy starting lane filtering */
  def startFiltering(): MicroMotorcycleState = {
    this.copy(filteringBetweenLanes = true)
  }
  
  /** Create a copy stopping lane filtering */
  def stopFiltering(): MicroMotorcycleState = {
    this.copy(filteringBetweenLanes = false)
  }
  
  /** Calculate effective minimum gap based on aggressiveness */
  def effectiveMinGap: Double = {
    minGap * (1.0 - aggressiveness * 0.3)  // Up to 30% reduction
  }
  
  /** Check if gap is acceptable for this motorcycle */
  def isGapAcceptable(gap: Double): Boolean = {
    gap >= effectiveMinGap
  }
}

object MicroMotorcycleState {
  /** Create an initial micro state for a motorcycle entering a MICRO link */
  def initial(
    lane: Int = 0,
    canFilterLanes: Boolean = true,
    aggressiveness: Double = 0.7,
    customDesiredVelocity: Option[Double] = None
  ): MicroMotorcycleState = {
    MicroMotorcycleState(
      positionInLink = 0.0,
      velocity = 0.0,
      acceleration = 0.0,
      currentLane = lane,
      canFilterLanes = canFilterLanes,
      aggressiveness = aggressiveness,
      desiredVelocity = customDesiredVelocity.getOrElse(16.67)
    )
  }
}
