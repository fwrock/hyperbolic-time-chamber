package org.interscity.htc
package core.entity.actor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.InitializeData
import org.interscity.htc.core.enumeration.ReportTypeEnum

import scala.collection.mutable

case class Initialization(
  id: String,
  resourceId: String,
  classType: String,
  data: Any,
  timeManagers: mutable.Map[String, ActorRef],
  creatorManager: ActorRef,
  reporters: mutable.Map[ReportTypeEnum, ActorRef],
  relationships: mutable.Map[String, ShardActorId] = mutable.Map[String, ShardActorId]()
) {

  def toInitializeData: InitializeData =
    InitializeData(
      data = data,
      resourceId = resourceId,
      creatorManager = creatorManager,
      reporters = reporters,
      relationships = relationships.map {
        case (label, rel) => rel.entityId -> rel
      },
      timeManagers = timeManagers
    )
}
