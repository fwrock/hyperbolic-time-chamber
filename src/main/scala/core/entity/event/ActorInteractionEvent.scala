package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import core.entity.event.data.BaseEventData

import org.interscity.htc.core.entity.actor.Identify

case class ActorInteractionEvent(
                                  tick: Tick,
                                  lamportTick: Tick,
                                  actorRefId: String,
                                  actorPathRef: String,
                                  actorClassType: String,
                                  eventType: String = "default",
                                  data: AnyRef
) {

  def toIdentity: Identify = Identify(
    id = actorRefId,
    classType = actorClassType,
    actorPathRef = actorPathRef
  )
}
