package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import core.entity.event.data.BaseEventData

import org.interscity.htc.core.entity.actor.Identify

case class ActorInteractionEvent[D <: BaseEventData](
  tick: Tick,
  lamportTick: Tick,
  actorRefId: String,
  actorRef: ActorRef,
  actorClassType: String,
  eventType: String = "default",
  data: D
) extends BaseEvent[D](
      tick = tick,
      lamportTick = lamportTick,
      actorRefId = actorRefId,
      actorRef = actorRef,
      actorClassType = actorClassType,
      data = data,
      eventType = eventType
    ) {
  
  def toIdentity() = Identify(
    id = actorRefId,
    classType = actorClassType,
    actorRef = actorRef
  )
}
