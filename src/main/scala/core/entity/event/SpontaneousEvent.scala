package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.{ EventId, SubTick, Tick }

import org.apache.pekko.actor.ActorRef

case class SpontaneousEvent(
  tick: Tick,
  actorRef: ActorRef
) extends BaseEvent(tick = tick, actorRef = actorRef)
