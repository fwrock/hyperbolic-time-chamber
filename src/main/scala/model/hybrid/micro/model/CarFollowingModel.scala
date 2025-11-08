package org.interscity.htc
package model.hybrid.micro.model

import org.interscity.htc.model.hybrid.entity.state.MicroMovableState

/** Car-following model interface.
  * 
  * Defines the contract for microscopic car-following behavior.
  * Models calculate safe velocities and accelerations based on:
  * - Leader vehicle state (position, velocity)
  * - Gap to leader
  * - Current vehicle state
  * - Vehicle capabilities (max accel/decel)
  * 
  * Implementations: KraussModel (default), IDM, Gipps, etc.
  */
trait CarFollowingModel {
  
  /** Calculate safe velocity considering leader vehicle.
    * 
    * @param currentVelocity Current velocity of ego vehicle (m/s)
    * @param desiredVelocity Desired free-flow velocity (m/s)
    * @param gap Gap to leader vehicle (meters)
    * @param leaderVelocity Leader vehicle velocity (m/s)
    * @param maxAcceleration Maximum acceleration (m/s²)
    * @param maxDeceleration Maximum deceleration (m/s²)
    * @param minGap Minimum safe gap (meters)
    * @param reactionTime Driver reaction time (seconds)
    * @param deltaT Time step duration (seconds)
    * @return Safe velocity (m/s)
    */
  def calculateSafeVelocity(
    currentVelocity: Double,
    desiredVelocity: Double,
    gap: Double,
    leaderVelocity: Double,
    maxAcceleration: Double,
    maxDeceleration: Double,
    minGap: Double,
    reactionTime: Double,
    deltaT: Double
  ): Double
  
  /** Calculate acceleration for next time step.
    * 
    * @param currentVelocity Current velocity (m/s)
    * @param desiredVelocity Desired velocity (m/s)
    * @param safeVelocity Safe velocity from calculateSafeVelocity (m/s)
    * @param maxAcceleration Maximum acceleration (m/s²)
    * @param maxDeceleration Maximum deceleration (m/s²)
    * @param deltaT Time step duration (seconds)
    * @return Acceleration (m/s²)
    */
  def calculateAcceleration(
    currentVelocity: Double,
    desiredVelocity: Double,
    safeVelocity: Double,
    maxAcceleration: Double,
    maxDeceleration: Double,
    deltaT: Double
  ): Double
  
  /** Update vehicle state based on car-following model.
    * 
    * Convenience method that applies full car-following logic.
    * 
    * @param state Current micro state
    * @param gap Gap to leader (meters)
    * @param leaderVelocity Leader velocity (m/s)
    * @param deltaT Time step (seconds)
    * @return Updated micro state
    */
  def updateState(
    state: MicroMovableState,
    gap: Double,
    leaderVelocity: Double,
    deltaT: Double
  ): (Double, Double, Double) // (newPosition, newVelocity, acceleration)
  
  /** Model name for debugging/logging.
    * 
    * @return Model name (e.g., "Krauss", "IDM", "Gipps")
    */
  def modelName: String
}

/** Factory for car-following models.
  */
object CarFollowingModel {
  
  /** Get default car-following model (Krauss).
    * 
    * @return Krauss model instance
    */
  def default: CarFollowingModel = KraussModel()
  
  /** Get car-following model by name.
    * 
    * @param name Model name ("krauss", "idm", "gipps")
    * @return Model instance
    */
  def byName(name: String): CarFollowingModel = name.toLowerCase match {
    case "krauss" => KraussModel()
    // Future: case "idm" => IDMModel()
    // Future: case "gipps" => GippsModel()
    case _ => KraussModel() // Default fallback
  }
}
