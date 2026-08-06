package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Data for lane change request or update.
  *
  * Used for lane change maneuvers in multi-lane scenarios.
  *
  * @param vehicleId
  *   Vehicle changing lanes
  * @param fromLane
  *   Origin lane
  * @param toLane
  *   Target lane
  * @param progress
  *   Lane change progress [0.0 - 1.0]
  * @param reason
  *   Reason for lane change (overtake, exit, etc.)
  * @param isComplete
  *   Whether lane change is complete
  */
case class LaneChangeData(
  vehicleId: String,
  fromLane: Int,
  toLane: Int,
  progress: Double,
  reason: String,
  isComplete: Boolean
) extends BaseEventData
