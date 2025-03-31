package org.interscity.htc
package core.entity.event.control.load

import core.entity.configuration.ActorDataSource

import org.apache.pekko.actor.ActorRef
import core.entity.event.BaseEvent

case class LoadDataEvent(
  actorRef: ActorRef,
  actorsDataSources: List[ActorDataSource]
) extends BaseEvent(actorRef = actorRef)
