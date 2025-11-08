package org.interscity.htc
package model.hybrid.entity.state

/** Base trait for microscopic state of all movable entities (vehicles, bicycles, etc.).
  * 
  * This trait defines the common interface for microscopic simulation state.
  * All vehicles in MICRO mode must implement this interface to work with
  * car-following models, lane-change models, and micro time managers.
  * 
  * The microscopic state includes:
  * - Kinematics: position, velocity, acceleration
  * - Perception: leader vehicle, gaps, leader velocity
  * - Parameters: max acceleration/deceleration, desired velocity, reaction time
  * - Vehicle characteristics: length, lane position
  */
trait MicroMovableState {
  
  // ========== Kinematics ==========
  
  /** Position in the link (meters from link start) */
  def positionInLink: Double
  
  /** Current velocity (m/s) */
  def velocity: Double
  
  /** Current acceleration (m/s²) */
  def acceleration: Double
  
  /** Current lane (0-indexed, 0 = leftmost lane) */
  def currentLane: Int
  
  // ========== Perception ==========
  
  /** ID of the vehicle ahead in the same lane */
  def leaderVehicle: Option[String]
  
  /** Gap to the leader vehicle (meters, bumper-to-bumper) */
  def gapToLeader: Double
  
  /** Velocity of the leader vehicle (m/s) */
  def leaderVelocity: Double
  
  // ========== Model Parameters ==========
  
  /** Maximum acceleration capability (m/s²) */
  def maxAcceleration: Double
  
  /** Maximum deceleration capability (m/s²) - positive value */
  def maxDeceleration: Double
  
  /** Minimum safe gap to maintain (meters) */
  def minGap: Double
  
  /** Desired (free-flow) velocity (m/s) */
  def desiredVelocity: Double
  
  /** Driver reaction time (seconds) */
  def reactionTime: Double
  
  // ========== Vehicle Characteristics ==========
  
  /** Length of the vehicle (meters) */
  def vehicleLength: Double
  
  // ========== Lane Change ==========
  
  /** Desired target lane for lane change (None if happy in current lane) */
  def desiredLane: Option[Int]
  
  /** Progress of lane change maneuver (0.0 = in original lane, 1.0 = in target lane) */
  def laneChangeProgress: Double
  
  // ========== Helper Methods ==========
  
  /** Calculate the rear position of the vehicle (front position - length) */
  def rearPosition: Double = positionInLink - vehicleLength
  
  /** Check if the vehicle has a leader */
  def hasLeader: Boolean = leaderVehicle.isDefined
  
  /** Check if the vehicle is currently changing lanes */
  def isChangingLanes: Boolean = laneChangeProgress > 0.0 && laneChangeProgress < 1.0
  
  /** Check if velocity is safe (non-negative and below light speed!) */
  def isSafeVelocity: Boolean = velocity >= 0.0 && velocity < 300_000_000.0  // m/s
  
  /** Check if the gap is safe (non-negative) */
  def isSafeGap: Boolean = gapToLeader >= 0.0
}
