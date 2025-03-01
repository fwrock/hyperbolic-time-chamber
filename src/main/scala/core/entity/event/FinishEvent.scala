package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify

case class FinishEvent(
  actorRef: ActorRef,
  identify: Identify,
  end: Tick,
  scheduleEvent: Option[ScheduleEvent] = None,
  timeManager: ActorRef = null,
  destruct: Boolean = false
) extends BaseEvent(tick = end, actorRef = actorRef)
