package org.interscity.htc
package model.hybrid.micro.lane

import org.interscity.htc.model.hybrid.entity.state.MicroMovableState
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}

import scala.math.max

/** MOBIL (Minimizing Overall Braking Induced by Lane Changes) lane-change model.
  * 
  * Reference: Kesting, Treiber, Helbing (2007)
  * "General Lane-Changing Model MOBIL for Car-Following Models"
  * 
  * MOBIL considers:
  * 1. Incentive criterion: Is there acceleration advantage?
  * 2. Safety criterion: Is rear gap safe?
  * 3. Politeness factor: How much to consider other drivers
  * 
  * Key parameters:
  * - politeness (p): Weight of other drivers' disadvantage [0.0 - 1.0]
  * - safeDeceleration (b_safe): Maximum safe deceleration for others
  * - accelerationThreshold (a_th): Minimum advantage to change lanes
  * 
  * @param politeness Politeness factor [0.0 - 1.0]
  * @param safeDeceleration Safe deceleration for followers (m/s²)
  * @param accelerationThreshold Min acceleration advantage (m/s²)
  * @param biasToRight Bias factor for keeping right [0.0 - 1.0]
  * @param laneChangeDuration Time to complete lane change (seconds)
  * @param carFollowingModel Car-following model for acceleration calculations
  */
case class MobilLaneChange(
  politeness: Double = 0.5,
  safeDeceleration: Double = 4.0,
  accelerationThreshold: Double = 0.1,
  biasToRight: Double = 0.2,
  laneChangeDuration: Double = 2.0,
  carFollowingModel: CarFollowingModel = KraussModel()
) extends LaneChangeModel {
  
  override def modelName: String = "MOBIL"
  
  override def evaluateLaneChange(
    vehicleState: MicroMovableState,
    currentLane: Int,
    leaderInCurrentLane: Option[(String, Double, Double)],
    followerInCurrentLane: Option[(String, Double, Double)],
    leaderInTargetLane: Option[(String, Double, Double)],
    followerInTargetLane: Option[(String, Double, Double)],
    targetLane: Int,
    numberOfLanes: Int,
    laneRestrictions: Map[Int, String]
  ): LaneChangeDecision = {
    
    // Check if lane is available
    if (!isLaneAvailable(vehicleState, targetLane, laneRestrictions)) {
      return LaneChangeDecision(false, None, "Lane restricted", 0.0)
    }
    
    // Check if target lane exists
    if (targetLane < 0 || targetLane >= numberOfLanes) {
      return LaneChangeDecision(false, None, "Lane out of bounds", 0.0)
    }
    
    // Safety criterion: Check if follower in target lane can brake safely
    val isSafe = followerInTargetLane match {
      case Some((_, gap, followerVel)) =>
        // Follower must be able to decelerate safely
        val requiredDeceleration = calculateRequiredDeceleration(
          followerVel,
          vehicleState.velocity,
          gap,
          vehicleState.vehicleLength
        )
        requiredDeceleration <= safeDeceleration
      case None => true // No follower, safe
    }
    
    if (!isSafe) {
      return LaneChangeDecision(false, None, "Unsafe for follower", 0.0)
    }
    
    // Incentive criterion: Calculate acceleration advantages
    
    // 1. Current acceleration in current lane
    val currentAccel = calculateAcceleration(
      vehicleState,
      leaderInCurrentLane
    )
    
    // 2. Prospective acceleration in target lane
    val targetAccel = calculateAcceleration(
      vehicleState,
      leaderInTargetLane
    )
    
    // 3. Current follower's acceleration in current lane
    val followerCurrentAccel = followerInCurrentLane.map { case (_, gap, followerVel) =>
      calculateFollowerAcceleration(followerVel, vehicleState.velocity, gap)
    }.getOrElse(0.0)
    
    // 4. Current follower's new acceleration after we change
    val followerNewAccel = followerInCurrentLane.map { case (_, gap, followerVel) =>
      leaderInCurrentLane match {
        case Some((_, leaderGap, leaderVel)) =>
          // Follower will now follow our current leader
          calculateFollowerAcceleration(followerVel, leaderVel, gap + leaderGap + vehicleState.vehicleLength)
        case None =>
          // Follower will have free road
          0.5 // Assume moderate positive acceleration
      }
    }.getOrElse(0.0)
    
    // 5. Target lane follower's current acceleration
    val targetFollowerCurrentAccel = followerInTargetLane.map { case (_, gap, followerVel) =>
      leaderInTargetLane match {
        case Some((_, leaderGap, leaderVel)) =>
          calculateFollowerAcceleration(followerVel, leaderVel, leaderGap)
        case None => 0.5
      }
    }.getOrElse(0.0)
    
    // 6. Target lane follower's new acceleration after we change
    val targetFollowerNewAccel = followerInTargetLane.map { case (_, gap, followerVel) =>
      calculateFollowerAcceleration(followerVel, vehicleState.velocity, gap)
    }.getOrElse(0.0)
    
    // MOBIL incentive criterion:
    // a_new - a_current + p * (Δa_current_follower + Δa_target_follower) + a_bias > a_th
    val incentive = (targetAccel - currentAccel) +
      politeness * ((followerNewAccel - followerCurrentAccel) + (targetFollowerNewAccel - targetFollowerCurrentAccel)) +
      calculateBias(currentLane, targetLane)
    
    if (incentive > accelerationThreshold) {
      val urgency = math.min(1.0, incentive / (2.0 * accelerationThreshold))
      val reason = if (targetLane < currentLane) "Overtaking (right)" else "Overtaking (left)"
      return LaneChangeDecision(true, Some(targetLane), reason, urgency)
    }
    
    LaneChangeDecision(false, None, "Insufficient incentive", 0.0)
  }
  
  /** Calculate acceleration for vehicle with given leader.
    */
  private def calculateAcceleration(
    vehicleState: MicroMovableState,
    leader: Option[(String, Double, Double)]
  ): Double = {
    val (gap, leaderVel) = leader match {
      case Some((_, g, v)) => (g, v)
      case None => (1000.0, vehicleState.desiredVelocity) // Free road
    }
    
    val safeVel = carFollowingModel.calculateSafeVelocity(
      currentVelocity = vehicleState.velocity,
      desiredVelocity = vehicleState.desiredVelocity,
      gap = gap,
      leaderVelocity = leaderVel,
      maxAcceleration = vehicleState.maxAcceleration,
      maxDeceleration = vehicleState.maxDeceleration,
      minGap = vehicleState.minGap,
      reactionTime = vehicleState.reactionTime,
      deltaT = 0.1
    )
    
    carFollowingModel.calculateAcceleration(
      currentVelocity = vehicleState.velocity,
      desiredVelocity = vehicleState.desiredVelocity,
      safeVelocity = safeVel,
      maxAcceleration = vehicleState.maxAcceleration,
      maxDeceleration = vehicleState.maxDeceleration,
      deltaT = 0.1
    )
  }
  
  /** Calculate follower acceleration (simplified).
    */
  private def calculateFollowerAcceleration(
    followerVel: Double,
    leaderVel: Double,
    gap: Double
  ): Double = {
    // Simplified: assume follower wants to maintain safe gap
    val desiredGap = 2.0 + followerVel * 1.0 // minGap + reactionTime * velocity
    val gapError = gap - desiredGap
    
    // Proportional controller
    val accel = 0.5 * gapError + 0.3 * (leaderVel - followerVel)
    
    // Constrain to reasonable limits
    max(-4.0, math.min(2.0, accel))
  }
  
  /** Calculate required deceleration for follower.
    */
  private def calculateRequiredDeceleration(
    followerVel: Double,
    leaderVel: Double,
    gap: Double,
    leaderLength: Double
  ): Double = {
    val effectiveGap = max(0.1, gap - leaderLength)
    val relativeVel = followerVel - leaderVel
    
    if (relativeVel <= 0) return 0.0 // Follower is slower, no deceleration needed
    
    // Required deceleration: v² = 2 * a * d
    val requiredDecel = (relativeVel * relativeVel) / (2.0 * effectiveGap)
    requiredDecel
  }
  
  /** Calculate lane change bias (prefer right lanes).
    */
  private def calculateBias(currentLane: Int, targetLane: Int): Double = {
    if (targetLane < currentLane) {
      biasToRight // Incentive to move right
    } else {
      0.0 // No bias for left moves
    }
  }
  
  override def isLaneAvailable(
    vehicleState: MicroMovableState,
    targetLane: Int,
    laneRestrictions: Map[Int, String]
  ): Boolean = {
    laneRestrictions.get(targetLane) match {
      case Some("bus_lane") => false // Would need vehicle type check
      case Some("bike_lane") => false
      case Some("emergency") => false
      case _ => true
    }
  }
  
  override def updateLaneChangeProgress(
    currentProgress: Double,
    deltaT: Double
  ): Double = {
    val increment = deltaT / laneChangeDuration
    math.min(1.0, currentProgress + increment)
  }
}

/** MOBIL model companion object.
  */
object MobilLaneChange {
  
  /** Create MOBIL model with default parameters.
    */
  def apply(): MobilLaneChange = MobilLaneChange(
    politeness = 0.5,
    safeDeceleration = 4.0,
    accelerationThreshold = 0.1,
    biasToRight = 0.2,
    laneChangeDuration = 2.0,
    carFollowingModel = KraussModel()
  )
  
  /** Create aggressive MOBIL model (low politeness).
    */
  def aggressive: MobilLaneChange = MobilLaneChange(
    politeness = 0.1,
    safeDeceleration = 4.0,
    accelerationThreshold = 0.05,
    biasToRight = 0.1,
    laneChangeDuration = 1.5,
    carFollowingModel = KraussModel()
  )
  
  /** Create polite MOBIL model (high politeness).
    */
  def polite: MobilLaneChange = MobilLaneChange(
    politeness = 0.8,
    safeDeceleration = 3.5,
    accelerationThreshold = 0.2,
    biasToRight = 0.3,
    laneChangeDuration = 2.5,
    carFollowingModel = KraussModel()
  )
}
