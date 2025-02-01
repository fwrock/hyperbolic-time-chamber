package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef

import core.entity.event.data.BaseEventData

case class ActorInteractionEvent[D <: BaseEventData](
  tick: Tick,
  lamportTick: Tick,
  actorRefId: String,
  actorRef: ActorRef,
  eventType: String = "default",
  data: D
) extends BaseEvent[D](
      tick = tick,
      lamportTick = lamportTick,
      actorRefId = actorRefId,
      actorRef = actorRef,
      data = data,
      eventType = eventType
    )
