package org.interscity.htc
package core.entity.actor

case class Identify(
                     id: String,
                     classType: String,
                     actorPathRef: String = null
) {
  def toDependency: Dependency =
    Dependency(id = id, classType = classType)
}
