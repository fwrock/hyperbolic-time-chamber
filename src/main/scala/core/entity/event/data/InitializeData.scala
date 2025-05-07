package org.interscity.htc
package core.entity.event.data

import org.htc.protobuf.core.entity.actor.Dependency
import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.enumeration.ReportTypeEnum

import scala.collection.mutable

case class InitializeData(
  data: Any,
  resourceId: String,
  timeManager: ActorRef,
  creatorManager: ActorRef,
  reporters: mutable.Map[ReportTypeEnum, ActorRef],
  dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency]()
) extends BaseEventData
