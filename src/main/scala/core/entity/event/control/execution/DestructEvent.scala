package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent
import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class  DestructEvent(tick: Tick = Long.MinValue, actorRef: ActorRef)
    extends BaseEvent[DefaultBaseEventData](tick = tick, actorRef = actorRef)
