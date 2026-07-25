package org.interscity.htc
package core.entity.event

import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent

/** @param generation
  *   the dispatch generation (see [[SpontaneousEvent.generation]]) the sending actor was on when
  *   it decided to resolve — i.e. whatever `SpontaneousEvent.generation` it most recently
  *   received, echoed back verbatim, regardless of whether this `FinishEvent` was triggered
  *   directly from `actSpontaneous` or later from an async reply handler. Lets
  *   `LocalTimeManagerBase.finishEvent` detect and drop a `FinishEvent` belonging to a dispatch
  *   cycle the actor has already been redispatched past.
  */
case class FinishEvent(
  actorRef: ActorRef,
  identify: Identify,
  end: Tick,
  scheduleTick: Option[String] = None,
  scheduleEvent: Option[ScheduleEvent] = None,
  timeManager: ActorRef = null,
  destruct: Boolean = false,
  eventsAmount: Long = 0,
  generation: Long = 0L
) extends BaseEvent(tick = end, actorRef = actorRef)
