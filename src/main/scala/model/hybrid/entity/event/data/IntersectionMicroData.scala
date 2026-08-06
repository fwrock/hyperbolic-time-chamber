package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Data for microscopic intersection coordination.
  *
  * Used for conflict zone management at intersections.
  *
  * @param intersectionId
  *   Intersection node ID
  * @param conflictZoneId
  *   Conflict zone ID
  * @param vehicleId
  *   Vehicle requesting entry
  * @param entryLink
  *   Link entering from
  * @param exitLink
  *   Link exiting to
  * @param estimatedArrivalTime
  *   ETA at conflict zone
  * @param priority
  *   Vehicle priority
  * @param canEnter
  *   Whether vehicle can enter conflict zone
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
) extends BaseEventData
