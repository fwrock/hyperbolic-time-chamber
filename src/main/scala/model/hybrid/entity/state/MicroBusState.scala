package org.interscity.htc
package model.hybrid.entity.state

/** Microscopic state for a bus in MICRO simulation mode.
  * 
  * Buses have different characteristics than cars:
  * - Longer vehicle length (12m vs 4.5m)
  * - Lower acceleration/deceleration
  * - Larger minimum gaps
  * - Slower desired velocity
  * - Often restricted to bus lanes
  * - Interactions with bus stops
  * 
  * @param positionInLink Position from link start (meters)
  * @param velocity Current velocity (m/s)
  * @param acceleration Current acceleration (m/s²)
  * @param currentLane Current lane number (0-indexed)
  * @param leaderVehicle ID of the vehicle ahead
  * @param gapToLeader Gap to leader (meters)
  * @param leaderVelocity Velocity of the leader (m/s)
  * @param maxAcceleration Maximum acceleration (default: 1.2 m/s² - slower than car)
  * @param maxDeceleration Maximum deceleration (default: 3.5 m/s²)
  * @param minGap Minimum safe gap (default: 3.0 m - larger than car)
  * @param desiredVelocity Desired velocity (default: 11.11 m/s = 40 km/h)
  * @param reactionTime Driver reaction time (default: 1.5 s - longer than car)
  * @param vehicleLength Bus length (default: 12.0 m - much longer than car)
  * @param capacity Passenger capacity
  * @param currentPassengers Current number of passengers
  * @param nextBusStop ID of the next bus stop
  * @param busLaneRestricted Whether restricted to bus lanes
  * @param desiredLane Target lane for lane change
  * @param laneChangeProgress Progress of lane change (0.0 to 1.0)
  * @param canChangeLane Whether bus can change lanes (usually restricted)
  */
case class MicroBusState(
  positionInLink: Double,
  velocity: Double,
  acceleration: Double,
  currentLane: Int,
  leaderVehicle: Option[String] = None,
  gapToLeader: Double = Double.MaxValue,
  leaderVelocity: Double = 0.0,
  
  // Bus-specific parameters
  maxAcceleration: Double = 1.2,      // m/s² (slower than car)
  maxDeceleration: Double = 3.5,      // m/s²
  minGap: Double = 3.0,               // m (larger than car)
  desiredVelocity: Double = 11.11,    // m/s (40 km/h)
  reactionTime: Double = 1.5,         // s (longer than car)
  vehicleLength: Double = 12.0,       // m (much longer than car)
  
  // Bus-specific state
  capacity: Int = 80,
  currentPassengers: Int = 0,
  nextBusStop: Option[String] = None,
  busLaneRestricted: Boolean = true,
  
  // Lane change (more restricted for buses)
  desiredLane: Option[Int] = None,
  laneChangeProgress: Double = 0.0,
  canChangeLane: Boolean = false      // Usually restricted to bus lanes
) extends MicroMovableState {
  
  /** Create a copy with updated kinematics */
  def withUpdatedKinematics(
    newPosition: Double,
    newVelocity: Double,
    newAcceleration: Double
  ): MicroBusState = {
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
  ): MicroBusState = {
    this.copy(
      leaderVehicle = leaderId,
      gapToLeader = gap,
      leaderVelocity = leaderVel
    )
  }
  
  /** Create a copy with updated passenger count */
  def withPassengers(count: Int): MicroBusState = {
    this.copy(currentPassengers = count.min(capacity))
  }
  
  /** Check if bus is approaching a stop */
  def isApproachingStop(stopPosition: Double, threshold: Double = 50.0): Boolean = {
    nextBusStop.isDefined && (stopPosition - positionInLink) <= threshold
  }
  
  /** Calculate occupancy percentage */
  def occupancyPercentage: Double = {
    if (capacity > 0) (currentPassengers.toDouble / capacity.toDouble) * 100.0
    else 0.0
  }
}

object MicroBusState {
  /** Create an initial micro state for a bus entering a MICRO link */
  def initial(
    lane: Int = 0,
    capacity: Int = 80,
    passengers: Int = 0,
    busLaneRestricted: Boolean = true
  ): MicroBusState = {
    MicroBusState(
      positionInLink = 0.0,
      velocity = 0.0,
      acceleration = 0.0,
      currentLane = lane,
      capacity = capacity,
      currentPassengers = passengers,
      busLaneRestricted = busLaneRestricted
    )
  }
}
