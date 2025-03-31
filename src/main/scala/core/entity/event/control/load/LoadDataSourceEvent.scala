package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import core.entity.event.BaseEvent

import core.entity.configuration.ActorDataSource

case class LoadDataSourceEvent(
  actorDataSource: ActorDataSource,
  managerRef: ActorRef,
  creatorRef: ActorRef
) extends BaseEvent(actorRef = managerRef)
