package org.interscity.htc
package model.mobility.entity.event.data

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.model.mobility.entity.state.enumeration.ActorTypeEnum
import org.interscity.htc.core.entity.event.data.BaseEventData

final case class EnterLinkData(
  actorRef: ActorRef,
  actorId: String,
  actorType: ActorTypeEnum,
  actorSize: Double
) extends BaseEventData
