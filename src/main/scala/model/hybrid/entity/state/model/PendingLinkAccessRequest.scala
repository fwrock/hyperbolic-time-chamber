package org.interscity.htc
package model.hybrid.entity.state.model

/** Minimal record of a vehicle buffered at a Node waiting for downstream link capacity — exactly
  * what `sendMessageFn(entityId, shardId, data, eventType)` needs to grant access later. See
  * docs/CONGESTION_PROPAGATION_DESIGN.md.
  */
case class PendingLinkAccessRequest(
  actorRefId: String,
  shardRefId: String
)
