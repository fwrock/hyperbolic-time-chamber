package org.interscity.htc
package core.entity.event.control.execution

import org.apache.pekko.actor.ActorRef

case class TimeManagerRegisterEvent(
  actorRef: ActorRef
)
