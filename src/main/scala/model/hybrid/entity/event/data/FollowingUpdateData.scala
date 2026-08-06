package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Data for car-following update.
  *
  * Contains detailed car-following calculations.
  *
  * @param vehicleId
  *   Vehicle being updated
  * @param leaderVehicleId
  *   Leader vehicle (if any)
  * @param gap
  *   Gap to leader
  * @param leaderVelocity
  *   Leader velocity
  * @param safeVelocity
  *   Calculated safe velocity
  * @param desiredAcceleration
  *   Desired acceleration
  * @param appliedAcceleration
  *   Applied acceleration (after constraints)
  * @param modelUsed
  *   Car-following model name (e.g., "Krauss", "IDM")
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
) extends BaseEventData
