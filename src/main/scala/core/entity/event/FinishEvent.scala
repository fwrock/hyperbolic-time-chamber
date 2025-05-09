package org.interscity.htc
package core.entity.event

import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent

case class FinishEvent(
  actorRef: ActorRef,
  identify: Identify,
  end: Tick,
  scheduleTick: Option[String] = None,
  scheduleEvent: Option[ScheduleEvent] = None,
  timeManager: ActorRef = null,
  destruct: Boolean = false
) extends BaseEvent(tick = end, actorRef = actorRef)
