package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent
import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef

case class LocalTimeReportEvent(
  tick: Tick,
  actorRef: ActorRef
) extends BaseEvent(tick = tick, actorRef = actorRef)
