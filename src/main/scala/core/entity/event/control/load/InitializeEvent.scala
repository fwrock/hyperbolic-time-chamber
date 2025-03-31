package org.interscity.htc
package core.entity.event.control.load

import core.entity.event.BaseEvent

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.InitializeData

case class InitializeEvent(
  id: String,
  actorRef: ActorRef,
  data: InitializeData
) extends BaseEvent[InitializeData](actorRef = actorRef, data = data)
