package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.{ EventId, Tick, TickOffset }

import org.apache.pekko.actor.ActorRef

case class ScheduleEvent(
  tick: Tick,
  actorRef: ActorRef
) extends BaseEvent(tick = tick, actorRef = actorRef)
