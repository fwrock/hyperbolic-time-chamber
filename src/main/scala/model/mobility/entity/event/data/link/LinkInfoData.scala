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
  delegatedExitTick: Long = -1L,
  /** False when the Link will not answer this vehicle's `LeaveLinkData` (plain MESO link, vehicle opted in). */
  leaveAckRequired: Boolean = true,
  /** The tick the vehicle entered the link when the Link held this reply until the exit tick, or -1. */
  entryTick: Long = -1L,
  /** The Node's answer to the access request the Link made for the vehicle at the exit tick. */
  access: Option[model.mobility.entity.event.node.LinkAccessData] = None,
  /** The Link already removed the vehicle from the link when it saw the Node's grant: the vehicle sends no leave. */
  leaveDone: Boolean = false
) extends BaseEventData
