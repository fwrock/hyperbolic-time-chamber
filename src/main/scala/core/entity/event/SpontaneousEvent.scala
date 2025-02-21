package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.{EventId, SubTick, Tick}

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class SpontaneousEvent(
  tick: Tick,
  actorRef: ActorRef
) extends BaseEvent[DefaultBaseEventData](tick = tick, actorRef = actorRef)
