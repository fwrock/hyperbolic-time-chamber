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
  * @param requestSeq
  *   Echo of the `ActorInteractionEvent.seq` the originating `StartTrip` request carried (`0L`
  *   outside Time Warp, where this field is never consulted) -- lets the owning Person's
  *   `isAwaitingReply` guard distinguish a genuine reply to its currently-outstanding `StartTrip`
  *   from a rollback-orphaned reply to a since-retracted one, the same identity mechanism
  *   `LinkAccessData.requestSeq`/`LinkInfoData.requestSeq` already give Car/Bus/Bicycle/Motorcycle/
  *   Subway (see `docs/TIME_WARP_DESIGN.md`'s 2026-09-06 live-incident entry).
  */
case class TripCompletedData(
  vehicleId: Long,
  personId: Long,
  distanceTraveled: Double,
  travelTime: Long,
  finalNode: Long,
  completionTick: Tick,
  completionReason: String,
  wasTeleported: Boolean = false,
  requestSeq: Long = 0L
) extends BaseEventData
