package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef

case class Dependency(
  id: String,
  classType: String
) {
  def toIdentify(actorRef: ActorRef = null): Identify =
    Identify(id = id, classType = classType, actorPathRef = actorRef.path.name)
}
