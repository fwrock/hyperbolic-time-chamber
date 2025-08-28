package org.interscity.htc
package core.entity.actor.properties

import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimePolicyEnum }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

import scala.collection.mutable

case class Properties(
  entityId: String = null,
  var resourceId: String = null,
  timeManager: ActorRef = null,
  creatorManager: ActorRef = null,
  reporters: mutable.Map[ReportTypeEnum, ActorRef] = null,
  data: Any = null,
  dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency](),
  actorType: CreationTypeEnum = LoadBalancedDistributed,
  timePolicy: Option[TimePolicyEnum.TimePolicyEnum] = None
)
