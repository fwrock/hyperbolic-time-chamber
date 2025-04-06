package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef

case class LoadDataCreatorRegisterEvent(
  actorRef: ActorRef
)
