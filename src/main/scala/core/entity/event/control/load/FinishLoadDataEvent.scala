package org.interscity.htc
package core.entity.event.control.load

import core.entity.event.BaseEvent
import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class FinishLoadDataEvent(actorRef: ActorRef) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
