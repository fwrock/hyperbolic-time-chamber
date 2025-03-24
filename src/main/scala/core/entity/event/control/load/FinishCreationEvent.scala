package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class FinishCreationEvent(
  actorRef: ActorRef
) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
