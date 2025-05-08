package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef

case class FinishCreationEvent(
  actorRef: ActorRef,
  batchId: String,
  amount: Long
)
