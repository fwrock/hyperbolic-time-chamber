package org.interscity.htc
package model.hybrid.entity.event.data

import org.apache.pekko.actor.ActorRef
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.core.enumeration.CreationTypeEnum

final case class EnterLinkData(
  shardId: String,
  actorId: String,
  actorType: ActorTypeEnum,
  actorCreationType: CreationTypeEnum,
  actorSize: Double
) extends BaseEventData
