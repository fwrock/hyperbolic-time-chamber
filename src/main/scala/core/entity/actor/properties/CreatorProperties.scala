package org.interscity.htc
package core.entity.actor.properties

import core.enumeration.{ CreationTypeEnum, ReportTypeEnum }

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.enumeration.CreationTypeEnum.Simple

import scala.collection.mutable

case class CreatorProperties(
  entityId: String = null,
  shardId: String = null,
  loadDataManager: ActorRef = null,
  timeManager: ActorRef = null,
  creatorManager: ActorRef = null,
  reporters: mutable.Map[ReportTypeEnum, ActorRef] = null,
  data: Any = null,
  actorType: CreationTypeEnum = Simple
)
