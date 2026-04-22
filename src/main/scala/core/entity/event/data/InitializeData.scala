package org.interscity.htc
package core.entity.event.data

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.enumeration.ReportTypeEnum

import scala.collection.mutable

case class InitializeData(
  data: Any,
  resourceId: String,
  creatorManager: ActorRef,
  reporters: mutable.Map[ReportTypeEnum, ActorRef],
  relationships: mutable.Map[String, ShardActorId] = mutable.Map[String, ShardActorId](),
  timeManagers: mutable.Map[String, ActorRef] = null // Suporta múltiplos time managers
) extends BaseEventData
