package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor
import org.interscity.htc.core.entity.event.data.InitializeData
import org.interscity.htc.core.enumeration.ReportTypeEnum

import scala.collection.mutable

case class Initialization(
  id: String,
  shardId: String,
  classType: String,
  data: Any,
  timeManager: ActorRef,
  creatorManager: ActorRef,
  reporters: mutable.Map[ReportTypeEnum, ActorRef],
  dependencies: mutable.Map[String, actor.Dependency] = mutable.Map[String, actor.Dependency]()
) {

  def toInitializeData: InitializeData =
    InitializeData(
      data = data,
      shardId = shardId,
      timeManager = timeManager,
      creatorManager = creatorManager,
      reporters = reporters,
      dependencies = dependencies.map {
        case (label, dep) => dep.id -> dep
      }
    )
}
