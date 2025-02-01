package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef

case class Identify(
  id: String,
  actorRef: ActorRef
)
