package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.InitializeData

import scala.collection.mutable

case class Initialization(
  id: String,
  classType: String,
  data: Any,
  timeManager: ActorRef,
  dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency]()
) {

  def toInitializeData: InitializeData =
    InitializeData(
      data = data,
      timeManager = timeManager,
      dependencies = dependencies.map {
        case (label, dep) => dep.id -> dep
      }
    )
}
