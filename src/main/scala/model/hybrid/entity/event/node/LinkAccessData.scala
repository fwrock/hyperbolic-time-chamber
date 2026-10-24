package org.interscity.htc
package model.hybrid.entity.event.node

import core.entity.event.data.BaseEventData

import org.interscity.htc.core.types.Tick
import model.hybrid.entity.state.enumeration.{ LinkCapacityStateEnum, TrafficSignalPhaseStateEnum }

/** Node's reply to `RequestLinkAccessData`, and the same shape used unsolicited when the Node
  * later grants a vehicle it had buffered for downstream link capacity (see
  * `NodeEventHandler.tryDrainCapacityQueue`).
  *
  * `capacityState` only matters when `phase == Green` — while Red, the vehicle isn't crossing
  * yet regardless, so capacity isn't checked at request time.
  *
  * @param requestSeq
  *   the identity of the `RequestLinkAccessData` this answers -- the requester's own
  *   `ActorInteractionEvent.seq` for that send, echoed back verbatim (immediately from `event.seq`
  *   at request time, or from the buffered `PendingLinkAccessRequest.requestSeq` for a later
  *   capacity-freed grant). Lets the requester's `isAwaitingReply` reject this as stale when a
  *   rollback-triggered resend has since superseded the request it answers -- see
  *   `AwaitedReply.sendSeq`'s doc. `0L` outside Time Warp, where this field is never consulted.
  */
case class LinkAccessData(
  phase: TrafficSignalPhaseStateEnum,
  nextTick: Tick,
  queuePosition: Int = 0,
  capacityState: LinkCapacityStateEnum = LinkCapacityStateEnum.Available,
  requestSeq: Long = 0L
) extends BaseEventData
