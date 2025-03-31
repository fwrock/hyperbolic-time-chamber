package org.interscity.htc
package core.entity.actor

import org.htc.protobuf.core.entity.actor
import org.interscity.htc.core.entity.event.data.InitializeData

import scala.collection.mutable

case class Initialization(
  id: String,
  classType: String,
  data: Any,
  dependencies: mutable.Map[String, actor.Dependency] = mutable.Map[String, actor.Dependency]()
) {

  def toInitializeData: InitializeData =
    InitializeData(data = data, dependencies = dependencies.map { case (label, dep) => dep.id -> dep })
}
