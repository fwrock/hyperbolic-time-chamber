package org.interscity.htc
package model.hybrid.micro.lane

import org.interscity.htc.model.hybrid.entity.state.MicroMovableState

/** Lane change decision result.
  * 
  * @param shouldChange Whether vehicle should change lanes
  * @param targetLane Target lane (if shouldChange = true)
  * @param reason Reason for decision
  * @param urgency Urgency level [0.0 - 1.0]
  */
case class LaneChangeDecision(
  shouldChange: Boolean,
  targetLane: Option[Int],
  reason: String,
  urgency: Double = 0.0
)

/** Lane-change model interface.
  * 
  * Defines the contract for lane-change behavior.
  * Models decide when and where to change lanes based on:
  * - Current lane conditions (gap to leader, velocity)
  * - Adjacent lane conditions (gaps, velocities)
  * - Lane preferences (bus lanes, bike lanes)
  * - Route requirements (exit lanes)
  * 
  * Implementations: MobilLaneChange (MOBIL model), SimpleLaneChange
  */
trait LaneChangeModel {
  
  /** Evaluate lane change decision.
    * 
    * @param vehicleState Current vehicle state
    * @param currentLane Current lane
    * @param leaderInCurrentLane Leader in current lane
    * @param followerInCurrentLane Follower in current lane
    * @param leaderInTargetLane Leader in target lane (if any)
    * @param followerInTargetLane Follower in target lane (if any)
    * @param targetLane Target lane to evaluate
    * @param numberOfLanes Total lanes available
    * @param laneRestrictions Lane restrictions (e.g., bus lane)
    * @return Lane change decision
    */
  def evaluateLaneChange(
    vehicleState: MicroMovableState,
    currentLane: Int,
    leaderInCurrentLane: Option[(String, Double, Double)], // (id, gap, velocity)
    followerInCurrentLane: Option[(String, Double, Double)],
    leaderInTargetLane: Option[(String, Double, Double)],
    followerInTargetLane: Option[(String, Double, Double)],
    targetLane: Int,
    numberOfLanes: Int,
    laneRestrictions: Map[Int, String] = Map.empty
  ): LaneChangeDecision
  
  /** Check if lane is available for vehicle.
    * 
    * @param vehicleState Vehicle state
    * @param targetLane Target lane
    * @param laneRestrictions Lane restrictions
    * @return True if lane is available
    */
  def isLaneAvailable(
    vehicleState: MicroMovableState,
    targetLane: Int,
    laneRestrictions: Map[Int, String]
  ): Boolean
  
  /** Calculate lane change progress per time step.
    * 
    * @param currentProgress Current progress [0.0 - 1.0]
    * @param deltaT Time step (seconds)
    * @return New progress [0.0 - 1.0]
    */
  def updateLaneChangeProgress(
    currentProgress: Double,
    deltaT: Double
  ): Double
  
  /** Model name for debugging/logging.
    * 
    * @return Model name (e.g., "MOBIL", "Simple")
    */
  def modelName: String
}

/** Factory for lane-change models.
  */
object LaneChangeModel {
  
  /** Get default lane-change model (MOBIL).
    * 
    * @return MOBIL model instance
    */
  def default: LaneChangeModel = MobilLaneChange()
  
  /** Get lane-change model by name.
    * 
    * @param name Model name ("mobil", "simple")
    * @return Model instance
    */
  def byName(name: String): LaneChangeModel = name.toLowerCase match {
    case "mobil" => MobilLaneChange()
    case "simple" => SimpleLaneChange()
    case _ => MobilLaneChange() // Default fallback
  }
}

/** Simple lane-change model (basic implementation).
  * 
  * Simple rules:
  * - Change to faster lane if gap is safe
  * - Prefer right lane when possible (keep right rule)
  * - Respect lane restrictions
  */
case class SimpleLaneChange(
  minGapForChange: Double = 5.0,
  laneChangeDuration: Double = 2.0
) extends LaneChangeModel {
  
  override def modelName: String = "Simple"
  
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
    
    // Check gaps in target lane
    val frontGapSafe = leaderInTargetLane match {
      case Some((_, gap, _)) => gap > minGapForChange
      case None => true // No leader, front is clear
    }
    
    val rearGapSafe = followerInTargetLane match {
      case Some((_, gap, _)) => gap > minGapForChange
      case None => true // No follower, rear is clear
    }
    
    if (!frontGapSafe || !rearGapSafe) {
      return LaneChangeDecision(false, None, "Insufficient gap", 0.0)
    }
    
    // Check if target lane is faster
    val currentLeaderVelocity = leaderInCurrentLane.map(_._3).getOrElse(vehicleState.desiredVelocity)
    val targetLeaderVelocity = leaderInTargetLane.map(_._3).getOrElse(vehicleState.desiredVelocity)
    
    if (targetLeaderVelocity > currentLeaderVelocity + 2.0) {
      return LaneChangeDecision(true, Some(targetLane), "Overtaking slower leader", 0.5)
    }
    
    // Keep right rule (prefer rightmost lane when free)
    if (targetLane < currentLane && targetLeaderVelocity >= vehicleState.desiredVelocity * 0.9) {
      return LaneChangeDecision(true, Some(targetLane), "Keep right", 0.2)
    }
    
    LaneChangeDecision(false, None, "No advantage", 0.0)
  }
  
  override def isLaneAvailable(
    vehicleState: MicroMovableState,
    targetLane: Int,
    laneRestrictions: Map[Int, String]
  ): Boolean = {
    laneRestrictions.get(targetLane) match {
      case Some("bus_lane") => false // Only buses can use (would need vehicle type check)
      case Some("bike_lane") => false // Only bikes can use
      case Some("emergency") => false // Emergency vehicles only
      case _ => true // Normal lane
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
