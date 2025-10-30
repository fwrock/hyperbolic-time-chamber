package org.interscity.htc
package core.entity.actor.properties

import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import core.enumeration.{ CreationTypeEnum, LocalTimeManagerTypeEnum, ReportTypeEnum, TimePolicyEnum }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum.DiscreteEventSimulation

import scala.collection.mutable

case class SimulationBaseProperties(
  entityId: String = null,
  var resourceId: String = null,
  var timeManager: ActorRef = null,
  creatorManager: ActorRef = null,
  reporters: mutable.Map[ReportTypeEnum, ActorRef] = null,
  localTimeManagers: mutable.Map[LocalTimeManagerTypeEnum, ActorRef] = null,
  localTimeManagerType: LocalTimeManagerTypeEnum = DiscreteEventSimulation,
  data: Any = null,
  dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency](),
  actorType: CreationTypeEnum = LoadBalancedDistributed,
  timePolicy: Option[TimePolicyEnum.TimePolicyEnum] = None
) extends BaseProperties(
      entityId = entityId,
      actorType = actorType
    )
