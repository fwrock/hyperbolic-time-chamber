package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef

case class Identify(
                     id: String,
                     classType: String,
                     actorPathRef: String = null
) {
  def toDependency: Dependency =
    Dependency(id = id, classType = classType)
}
