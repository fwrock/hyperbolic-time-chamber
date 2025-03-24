package org.interscity.htc
package core.entity.event.control.load

import core.entity.configuration.ActorDataSource

import org.apache.pekko.actor.ActorRef
import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class LoadDataEvent(
  actorRef: ActorRef,
  actorsDataSources: List[ActorDataSource]
) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
