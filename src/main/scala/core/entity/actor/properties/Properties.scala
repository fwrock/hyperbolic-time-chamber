package org.interscity.htc
package core.entity.actor.properties

import core.enumeration.{ CreationTypeEnum, ReportTypeEnum }

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

import scala.collection.mutable

case class Properties(
  entityId: String = null,
  shardId: String = null,
  timeManager: ActorRef = null,
  creatorManager: ActorRef = null,
  reporters: mutable.Map[ReportTypeEnum, ActorRef] = null,
  data: Any = null,
  actorType: CreationTypeEnum = LoadBalancedDistributed
)
