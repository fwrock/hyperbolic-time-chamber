package org.interscity.htc
package model.interscsimulator.entity.state.model

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.ActorTypeEnum

case class LinkRegister(
  actorId: String,
  actorRef: ActorRef,
  actorType: ActorTypeEnum,
  actorSize: Double
)
