package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent
import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify

case class RegisterActorEvent(
  startTick: Tick,
  actorRef: ActorRef,
  identify: Identify
) extends BaseEvent(tick = startTick, actorRef = actorRef)
