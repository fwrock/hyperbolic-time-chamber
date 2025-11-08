package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum

/** Data for entering a link in microscopic mode.
  * 
  * Sent from link to vehicle when entering a MICRO link.
  * Contains initial microscopic state information.
  * 
  * @param linkId Link being entered
  * @param mode Simulation mode (should be MICRO)
  * @param assignedLane Initial lane assignment
  * @param linkLength Total link length
  * @param speedLimit Link speed limit
  * @param numberOfLanes Total lanes available
  * @param microTimeStep Duration of sub-tick (seconds)
  * @param ticksPerGlobalTick Sub-ticks per global tick
  */
case class MicroEnterLinkData(
  linkId: String,
  mode: SimulationModeEnum,
  assignedLane: Int,
  linkLength: Double,
  speedLimit: Double,
  numberOfLanes: Int,
  microTimeStep: Double,
  ticksPerGlobalTick: Int
)

/** Data for leaving a link from microscopic mode.
  * 
  * Sent from link to vehicle when exiting a MICRO link.
  * Contains final microscopic statistics.
  * 
  * @param linkId Link being exited
  * @param finalPosition Position at exit (should be ≈ linkLength)
  * @param finalVelocity Velocity at exit
  * @param travelTime Total time spent in link
  * @param distanceTraveled Total distance (for verification)
  * @param averageSpeed Average speed during traversal
  */
case class MicroLeaveLinkData(
  linkId: String,
  finalPosition: Double,
  finalVelocity: Double,
  travelTime: Double,
  distanceTraveled: Double,
  averageSpeed: Double
)

/** Data for microscopic update within a link.
  * 
  * Sent from link to vehicle during sub-tick execution.
  * Contains updated kinematics and leader information.
  * 
  * @param subTick Current sub-tick number
  * @param position Updated position in link
  * @param velocity Updated velocity
  * @param acceleration Calculated acceleration
  * @param currentLane Current lane
  * @param leaderVehicle Leader vehicle ID (if any)
  * @param gapToLeader Gap to leader (meters)
  * @param leaderVelocity Leader velocity (m/s)
  * @param safeVelocity Safe velocity calculated by car-following model
  */
case class MicroUpdateData(
  subTick: Int,
  position: Double,
  velocity: Double,
  acceleration: Double,
  currentLane: Int,
  leaderVehicle: Option[String],
  gapToLeader: Double,
  leaderVelocity: Double,
  safeVelocity: Double
)

/** Data for microscopic step request from vehicle.
  * 
  * Sent from vehicle to link requesting micro simulation step.
  * Contains current state for calculations.
  * 
  * @param vehicleId Vehicle requesting step
  * @param currentPosition Current position
  * @param currentVelocity Current velocity
  * @param currentLane Current lane
  * @param desiredVelocity Desired free-flow velocity
  * @param maxAcceleration Maximum acceleration capability
  * @param maxDeceleration Maximum deceleration capability
  * @param minGap Minimum safe gap
  * @param vehicleLength Vehicle physical length
  */
case class MicroStepData(
  vehicleId: String,
  currentPosition: Double,
  currentVelocity: Double,
  currentLane: Int,
  desiredVelocity: Double,
  maxAcceleration: Double,
  maxDeceleration: Double,
  minGap: Double,
  vehicleLength: Double
)

/** Data for lane change request or update.
  * 
  * Used for lane change maneuvers in multi-lane scenarios.
  * 
  * @param vehicleId Vehicle changing lanes
  * @param fromLane Origin lane
  * @param toLane Target lane
  * @param progress Lane change progress [0.0 - 1.0]
  * @param reason Reason for lane change (overtake, exit, etc.)
  * @param isComplete Whether lane change is complete
  */
case class LaneChangeData(
  vehicleId: String,
  fromLane: Int,
  toLane: Int,
  progress: Double,
  reason: String,
  isComplete: Boolean
)

/** Data for car-following update.
  * 
  * Contains detailed car-following calculations.
  * 
  * @param vehicleId Vehicle being updated
  * @param leaderVehicleId Leader vehicle (if any)
  * @param gap Gap to leader
  * @param leaderVelocity Leader velocity
  * @param safeVelocity Calculated safe velocity
  * @param desiredAcceleration Desired acceleration
  * @param appliedAcceleration Applied acceleration (after constraints)
  * @param modelUsed Car-following model name (e.g., "Krauss", "IDM")
  */
case class FollowingUpdateData(
  vehicleId: String,
  leaderVehicleId: Option[String],
  gap: Double,
  leaderVelocity: Double,
  safeVelocity: Double,
  desiredAcceleration: Double,
  appliedAcceleration: Double,
  modelUsed: String
)

/** Data for microscopic intersection coordination.
  * 
  * Used for conflict zone management at intersections.
  * 
  * @param intersectionId Intersection node ID
  * @param conflictZoneId Conflict zone ID
  * @param vehicleId Vehicle requesting entry
  * @param entryLink Link entering from
  * @param exitLink Link exiting to
  * @param estimatedArrivalTime ETA at conflict zone
  * @param priority Vehicle priority
  * @param canEnter Whether vehicle can enter conflict zone
  */
case class IntersectionMicroData(
  intersectionId: String,
  conflictZoneId: String,
  vehicleId: String,
  entryLink: String,
  exitLink: String,
  estimatedArrivalTime: Double,
  priority: Int,
  canEnter: Boolean
)

/** Signal for completing all micro ticks in a global tick.
  * 
  * Broadcast from link to all vehicles when global tick completes.
  * 
  * @param linkId Link completing tick
  * @param globalTick Global tick number
  * @param totalSubTicks Number of sub-ticks executed
  */
case class MicroTicksCompleted(
  linkId: String,
  globalTick: Long,
  totalSubTicks: Int
)
