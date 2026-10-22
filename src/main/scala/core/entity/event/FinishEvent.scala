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
  * @param awaitingReply
  *   The sender resolved this dispatch with no next tick because it is waiting for
  *   a reply it registered (a non-empty awaited-reply registry), not because it is finished. The
  *   time manager must not report itself idle while any actor is in this state, or the termination
  *   detector cannot tell a finished actor from one still blocked on a reply.
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
  generation: Long = 0L,
  awaitingReply: Boolean = false
) extends BaseEvent(tick = end, actorRef = actorRef)
