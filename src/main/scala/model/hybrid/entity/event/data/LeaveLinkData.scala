package org.interscity.htc
package model.hybrid.entity.event.data

import model.hybrid.entity.state.enumeration.ActorTypeEnum

import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.core.enumeration.CreationTypeEnum

// shardId/actorId removed (recommendation 11, docs/EVENTS_MESSAGES_ANALYSIS.md): always equal to
// the sending actor's own getShardId/getEntityId, already carried by the ActorInteractionEvent
// envelope that contains this payload (actorRefId/shardRefId) — consumers read event.actorRefId/
// event.shardRefId instead.
case class LeaveLinkData(
  actorType: ActorTypeEnum,
  actorSize: Double,
  actorCreationType: CreationTypeEnum
) extends BaseEventData
