package org.interscity.htc
package core.entity.event

import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class ScheduleEvent(
  tick: Tick,
  actorRef: ActorRef,
  identify: Identify
) extends BaseEvent[DefaultBaseEventData](tick = tick, actorRef = actorRef)
