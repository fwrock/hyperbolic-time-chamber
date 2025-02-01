package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import core.entity.event.BaseEvent

case class FinishCreationEvent(
  actorRef: ActorRef
) extends BaseEvent(actorRef = actorRef)
