package org.interscity.htc
package core.entity.event.control.load

import core.entity.event.BaseEvent

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.RequestInitializeData

case class RequestInitializeEvent(
  actorRef: ActorRef,
  data: RequestInitializeData
) extends BaseEvent[RequestInitializeData](actorRef = actorRef, data = data)
