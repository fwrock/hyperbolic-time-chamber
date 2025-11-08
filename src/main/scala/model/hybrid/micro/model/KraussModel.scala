package org.interscity.htc
package model.hybrid.micro.model

import org.interscity.htc.model.hybrid.entity.state.MicroMovableState

import scala.math.{sqrt, min, max}
import scala.util.Random

/** Krauss car-following model (default).
  * 
  * Reference: Krauss (1998) - "Microscopic Modeling of Traffic Flow"
  * 
  * The Krauss model calculates safe velocity considering:
  * 1. Desired velocity (free-flow speed)
  * 2. Safe velocity based on leader gap
  * 3. Random factor for driver variability
  * 
  * Safe velocity formula:
  *   v_safe = -τ·b + √((τ·b)² + v_leader² + 2·b·gap)
  * 
  * where:
  *   τ = reaction time (typically 1.0s)
  *   b = max deceleration (typically 4.5 m/s²)
  *   gap = distance to leader minus min gap
  *   v_leader = leader velocity
  * 
  * The model then applies randomness and acceleration constraints.
  * 
  * @param randomness Randomness factor [0.0 - 1.0] for driver variability
  * @param epsilonAccel Small acceleration safety margin (m/s²)
  * @param random Random generator for variability
  */
case class KraussModel(
  randomness: Double = 0.2,
  epsilonAccel: Double = 0.5,
  random: Random = new Random()
) extends CarFollowingModel {
  
  override def modelName: String = "Krauss"
  
  override def calculateSafeVelocity(
    currentVelocity: Double,
    desiredVelocity: Double,
    gap: Double,
    leaderVelocity: Double,
    maxAcceleration: Double,
    maxDeceleration: Double,
    minGap: Double,
    reactionTime: Double,
    deltaT: Double
  ): Double = {
    
    // Effective gap (subtract minimum safe gap)
    val effectiveGap = max(0.0, gap - minGap)
    
    // If no effective gap, must decelerate to match leader or stop
    if (effectiveGap <= 0.0) {
      return max(0.0, leaderVelocity - maxDeceleration * deltaT)
    }
    
    // Krauss safe velocity formula
    // v_safe = -τ·b + √((τ·b)² + v_leader² + 2·b·gap)
    val tau_b = reactionTime * maxDeceleration
    val discriminant = tau_b * tau_b + leaderVelocity * leaderVelocity + 2.0 * maxDeceleration * effectiveGap
    
    val safeVelocity = if (discriminant >= 0.0) {
      -tau_b + sqrt(discriminant)
    } else {
      // Negative discriminant: emergency braking required
      0.0
    }
    
    // Safe velocity cannot be negative
    max(0.0, safeVelocity)
  }
  
  override def calculateAcceleration(
    currentVelocity: Double,
    desiredVelocity: Double,
    safeVelocity: Double,
    maxAcceleration: Double,
    maxDeceleration: Double,
    deltaT: Double
  ): Double = {
    
    // Maximum possible velocity considering acceleration constraint
    val maxPossibleVelocity = currentVelocity + maxAcceleration * deltaT
    
    // Target velocity is minimum of desired, safe, and max possible
    val targetVelocity = min(desiredVelocity, min(safeVelocity, maxPossibleVelocity))
    
    // Apply randomness (driver variability)
    val randomFactor = 1.0 - randomness * random.nextDouble()
    val randomizedTargetVelocity = targetVelocity * randomFactor
    
    // Calculate acceleration needed to reach target
    val acceleration = (randomizedTargetVelocity - currentVelocity) / deltaT
    
    // Constrain acceleration to vehicle capabilities
    val constrainedAcceleration = max(
      -maxDeceleration - epsilonAccel,
      min(maxAcceleration, acceleration)
    )
    
    constrainedAcceleration
  }
  
  override def updateState(
    state: MicroMovableState,
    gap: Double,
    leaderVelocity: Double,
    deltaT: Double
  ): (Double, Double, Double) = {
    
    val currentVelocity = state.velocity
    val currentPosition = state.positionInLink
    
    // Calculate safe velocity
    val safeVelocity = calculateSafeVelocity(
      currentVelocity = currentVelocity,
      desiredVelocity = state.desiredVelocity,
      gap = gap,
      leaderVelocity = leaderVelocity,
      maxAcceleration = state.maxAcceleration,
      maxDeceleration = state.maxDeceleration,
      minGap = state.minGap,
      reactionTime = state.reactionTime,
      deltaT = deltaT
    )
    
    // Calculate acceleration
    val acceleration = calculateAcceleration(
      currentVelocity = currentVelocity,
      desiredVelocity = state.desiredVelocity,
      safeVelocity = safeVelocity,
      maxAcceleration = state.maxAcceleration,
      maxDeceleration = state.maxDeceleration,
      deltaT = deltaT
    )
    
    // Update velocity (ensure non-negative)
    val newVelocity = max(0.0, currentVelocity + acceleration * deltaT)
    
    // Update position (use average velocity for accuracy)
    val averageVelocity = (currentVelocity + newVelocity) / 2.0
    val newPosition = currentPosition + averageVelocity * deltaT
    
    (newPosition, newVelocity, acceleration)
  }
}

/** Krauss model companion object.
  */
object KraussModel {
  
  /** Create Krauss model with default parameters.
    */
  def apply(): KraussModel = KraussModel(
    randomness = 0.2,
    epsilonAccel = 0.5,
    random = new Random()
  )
  
  /** Create Krauss model with custom randomness.
    * 
    * @param randomness Randomness factor [0.0 - 1.0]
    */
  def withRandomness(randomness: Double): KraussModel = KraussModel(
    randomness = max(0.0, min(1.0, randomness)),
    epsilonAccel = 0.5,
    random = new Random()
  )
  
  /** Create Krauss model with custom random seed (for reproducibility).
    * 
    * @param seed Random seed
    */
  def withSeed(seed: Long): KraussModel = KraussModel(
    randomness = 0.2,
    epsilonAccel = 0.5,
    random = new Random(seed)
  )
  
  /** Create deterministic Krauss model (no randomness).
    */
  def deterministic: KraussModel = KraussModel(
    randomness = 0.0,
    epsilonAccel = 0.5,
    random = new Random()
  )
}
