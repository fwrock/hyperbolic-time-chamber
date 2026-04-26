package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.configuration.ActorDataSource

import scala.collection.mutable

case class FinishLoadDataEvent(
  actorRef: ActorRef,
  amount: Long,
  actorClassType: String,
  creators: mutable.Set[ActorRef],
  progressiveSources: List[ActorDataSource] = List.empty,
  creatorRef: ActorRef = null,
  creatorPoolRef: ActorRef = null
)
