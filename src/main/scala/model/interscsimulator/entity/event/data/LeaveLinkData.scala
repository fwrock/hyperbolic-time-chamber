package org.interscity.htc
package model.interscsimulator.entity.event.data

import model.interscsimulator.entity.state.enumeration.ActorTypeEnum

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.BaseEventData

case class LeaveLinkData(
  actorRef: ActorRef,
  actorId: String,
  actorType: ActorTypeEnum,
  actorSize: Double
) extends BaseEventData
