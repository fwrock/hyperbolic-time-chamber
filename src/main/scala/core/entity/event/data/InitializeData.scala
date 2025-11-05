package org.interscity.htc
package core.entity.event.data

import org.htc.protobuf.core.entity.actor.Dependency
import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.enumeration.ReportTypeEnum

import scala.collection.mutable

case class InitializeData(
  data: Any,
  resourceId: String,
  creatorManager: ActorRef,
  reporters: mutable.Map[ReportTypeEnum, ActorRef],
  dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency](),
  timeManagers: mutable.Map[String, ActorRef] = null // Suporta múltiplos time managers
) extends BaseEventData
