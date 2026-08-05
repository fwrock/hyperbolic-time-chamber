package org.interscity.htc
package model.hybrid.entity.event.data.vehicle

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Sent by a vehicle being destroyed while `WaitingCapacity` (buffered at a Node for downstream
  * link capacity, never yet granted) so the Node can remove its stale `PendingLinkAccessRequest`
  * from `NodeState.capacityWaitQueue` — otherwise that entry sits forever, and if it's ever
  * dequeued for a freed slot, the grant goes to a dead actor (a dead-letter) and that capacity
  * slot is silently lost for the rest of the simulation, since no LeaveLinkData will ever arrive
  * from a vehicle that never actually entered the link. See
  * docs/CONGESTION_PROPAGATION_DESIGN.md.
  */
case class CancelLinkAccessRequestData(
  targetLinkId: String
) extends BaseEventData
