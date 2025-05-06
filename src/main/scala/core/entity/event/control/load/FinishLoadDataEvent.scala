package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef

import scala.collection.mutable

case class FinishLoadDataEvent(
  actorRef: ActorRef,
  amount: Long,
  actorClassType: String,
  creators: mutable.Set[ActorRef]
)
