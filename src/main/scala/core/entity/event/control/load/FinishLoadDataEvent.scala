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
  // Progressive loading: sources to be loaded during simulation (empty if none)
  progressiveSources: List[ActorDataSource] = List.empty,
  // Creator references for progressive loaders to reuse
  creatorRef: ActorRef = null,
  creatorPoolRef: ActorRef = null
)
