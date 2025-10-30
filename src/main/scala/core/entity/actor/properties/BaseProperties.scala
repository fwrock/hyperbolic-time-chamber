package org.interscity.htc
package core.entity.actor.properties

import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimePolicyEnum }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency

import scala.collection.mutable

abstract class BaseProperties(
  entityId: String = null,
  actorType: CreationTypeEnum = LoadBalancedDistributed
) {

  def getEntityId: String = entityId

  def getActorType: CreationTypeEnum = actorType
}
