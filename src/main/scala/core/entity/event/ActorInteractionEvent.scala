package org.interscity.htc
package core.entity.event

import core.types.Tick

import org.apache.pekko.actor.ActorRef
import core.entity.event.data.BaseEventData

import org.htc.protobuf.core.entity.actor.Identify

case class ActorInteractionEvent(
  tick: Tick,
  lamportTick: Tick,
  actorRefId: String,
  shardRefId: String,
  actorPathRef: String,
  actorClassType: String,
  eventType: String = "default",
  data: AnyRef
) {

  def toIdentity: Identify = Identify(
    id = actorRefId,
    classType = actorClassType,
    actorRef = actorPathRef
  )
}
