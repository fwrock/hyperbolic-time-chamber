package org.interscity.htc
package model.hybrid.entity.state.model

/** Minimal record of a vehicle buffered at a Node waiting for downstream link capacity — exactly
  * what `sendMessageFn(entityId, shardId, data, eventType)` needs to grant access later. See
  * docs/CONGESTION_PROPAGATION_DESIGN.md.
  *
  * @param requestSeq
  *   the buffered `RequestLinkAccessData`'s original `ActorInteractionEvent.seq`, carried forward
  *   so the eventual capacity-freed grant's `LinkAccessData.requestSeq` can still echo the correct
  *   request identity even though the grant fires long after the original request event is gone
  *   (see `LinkAccessData.requestSeq`'s doc and `AwaitedReply.sendSeq`'s doc). `0L` outside Time
  *   Warp, where this field is never consulted.
  * @param signalAlreadyCleared
  *   retain MICRO's completed crossing while waiting for capacity, including across rollback;
  *   a later red phase cannot undo a crossing already authorized by the approach Link.
  */
case class PendingLinkAccessRequest(
  actorRefId: Long,
  shardRefId: String,
  requestSeq: Long = 0L,
  signalAlreadyCleared: Boolean = false
)
