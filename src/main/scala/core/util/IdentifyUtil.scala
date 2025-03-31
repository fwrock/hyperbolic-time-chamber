package org.interscity.htc
package core.util

import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }

object IdentifyUtil {

  def fromDependency(dependency: Dependency, actorPathRef: String = null): Identify =
    Identify(
      id = dependency.id,
      classType = dependency.classType,
      actorRef = actorPathRef
    )

}
