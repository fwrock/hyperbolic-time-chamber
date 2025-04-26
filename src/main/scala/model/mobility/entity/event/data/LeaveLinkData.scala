package org.interscity.htc
package model.mobility.entity.event.data

import model.mobility.entity.state.enumeration.ActorTypeEnum

import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.core.enumeration.CreationTypeEnum

case class LeaveLinkData(
  shardId: String,
  actorId: String,
  actorType: ActorTypeEnum,
  actorSize: Double,
  actorCreationType: CreationTypeEnum
) extends BaseEventData
