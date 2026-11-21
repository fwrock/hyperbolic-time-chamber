package org.interscity.htc
package model.mobility.entity.event.data.link

import org.interscity.htc.core.entity.event.data.BaseEventData

/** @param requestSeq
  *   the identity of the `EnterLinkData`/`LeaveLinkData` this answers -- the requester's own
  *   `ActorInteractionEvent.seq` for that send, echoed back verbatim from `event.seq` at request
  *   time (MESO capacity admission/discharge can defer the terminal reply). Lets the requester's
  *   `isAwaitingReply` reject this as stale when a rollback-triggered resend has since superseded
  *   the request it answers -- see `AwaitedReply.sendSeq`'s doc. `0L` outside Time Warp, where this
  *   field is never consulted.
  */
final case class LinkInfoData(
  linkLength: Double = 0,
  linkCapacity: Double = 0,
  linkNumberOfCars: Int = 0,
  linkFreeSpeed: Double = 0,
  linkLanes: Int = 0,
  requestSeq: Long = 0L,
  mesoOriginAtEnd: Boolean = false,
  mesoEntryWaitSeconds: Double = 0.0,
  mesoExitWaitSeconds: Double = 0.0,
  /** The tick at which the Link already scheduled the vehicle's wake, or -1 when the vehicle must schedule itself. */
  delegatedExitTick: Long = -1L
) extends BaseEventData
