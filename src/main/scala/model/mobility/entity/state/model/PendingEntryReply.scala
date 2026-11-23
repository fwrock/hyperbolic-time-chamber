package org.interscity.htc
package model.mobility.entity.state.model

import model.mobility.entity.event.data.link.LinkInfoData

/** A vehicle admitted to a plain MESO link whose entry reply the Link sends at `exitTick`. It is an obligation to
  * answer that vehicle, so it lives in the Link's state.
  */
case class PendingEntryReply(
  exitTick: Long,
  actorRefId: Long,
  shardRefId: String,
  reply: LinkInfoData,
  accessRequest: Option[model.mobility.entity.event.data.LinkAccessRequest] = None
)
