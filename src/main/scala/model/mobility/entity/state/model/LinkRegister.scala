package org.interscity.htc
package model.mobility.entity.state.model

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.model.mobility.entity.state.enumeration.ActorTypeEnum

case class LinkRegister(
  actorId: String,
  actorRef: ActorRef,
  actorType: ActorTypeEnum,
  actorSize: Double
)
