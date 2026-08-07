package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Data for microscopic update within a link.
  *
  * Sent from link to vehicle during sub-tick execution. Contains updated kinematics and leader
  * information.
  *
  * @param subTick
  *   Current sub-tick number
  * @param position
  *   Updated position in link
  * @param velocity
  *   Updated velocity
  * @param acceleration
  *   Calculated acceleration
  * @param currentLane
  *   Current lane
  * @param leaderVehicle
  *   Leader vehicle ID (if any)
  * @param gapToLeader
  *   Gap to leader (meters)
  * @param leaderVelocity
  *   Leader velocity (m/s)
  * @param safeVelocity
  *   Safe velocity calculated by car-following model
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
) extends BaseEventData
