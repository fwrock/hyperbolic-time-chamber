package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.{ EventId, Tick, TickOffset }

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify

case class ScheduleEvent(
  tick: Tick,
  actorRef: ActorRef,
  identify: Identify
) extends BaseEvent(tick = tick, actorRef = actorRef)
