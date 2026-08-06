package org.interscity.htc
package model.hybrid.entity.event.data.person

import core.entity.event.data.BaseEventData
import core.types.Tick

/** Message from Vehicle to Person when trip is completed.
  *
  * Reports trip statistics back to the Person actor.
  *
  * @param vehicleId
  *   ID of the vehicle that completed the trip
  * @param personId
  *   ID of the person
  * @param distanceTraveled
  *   Distance traveled in meters
  * @param travelTime
  *   Time taken in ticks
  * @param finalNode
  *   Node ID where trip ended
  * @param completionTick
  *   Tick when trip completed
  * @param completionReason
  *   Why the trip ended ("reached_destination", "error", etc.)
  */
case class TripCompletedData(
  vehicleId: String,
  personId: String,
  distanceTraveled: Double,
  travelTime: Long,
  finalNode: String,
  completionTick: Tick,
  completionReason: String,
  wasTeleported: Boolean = false
) extends BaseEventData
