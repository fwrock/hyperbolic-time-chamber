package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class TimeManagerRegisterEvent(
  actorRef: ActorRef
) extends BaseEvent[DefaultBaseEventData]
