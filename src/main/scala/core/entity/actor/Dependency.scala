package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef

case class Dependency(
  id: String,
  classType: String,
  resourceId: String,
) {
  def toIdentify(actorRef: ActorRef = null): Identify =
    Identify(id = id, shardId = resourceId, classType = classType, actorRef = actorRef)
}
