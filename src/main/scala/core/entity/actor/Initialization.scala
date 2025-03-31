package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor
import org.interscity.htc.core.entity.event.data.InitializeData

import scala.collection.mutable

case class Initialization(
  id: String,
  classType: String,
  data: Any,
  timeManager: ActorRef,
  creatorManager: ActorRef,
  dependencies: mutable.Map[String, actor.Dependency] = mutable.Map[String, actor.Dependency]()
) {

  def toInitializeData: InitializeData =
    InitializeData(
      data = data,
      timeManager = timeManager,
      creatorManager = creatorManager,
      dependencies = dependencies.map {
        case (label, dep) => dep.id -> dep
      }
    )
}
