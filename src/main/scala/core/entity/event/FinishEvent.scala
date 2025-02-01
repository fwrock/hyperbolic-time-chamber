package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef

case class FinishEvent(
  actorRef: ActorRef,
  end: Tick,
  scheduleEvent: Option[ScheduleEvent] = None
) extends BaseEvent(tick = end, actorRef = actorRef)
