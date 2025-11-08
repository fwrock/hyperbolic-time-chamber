package org.interscity.htc
package model.hybrid.entity.state.model

import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.ActorTypeEnum

case class LinkRegister(
  actorId: String,
  shardId: String,
  actorType: ActorTypeEnum,
  actorCreationType: CreationTypeEnum,
  actorSize: Double
)
