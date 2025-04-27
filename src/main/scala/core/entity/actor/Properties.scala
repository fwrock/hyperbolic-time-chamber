package org.interscity.htc
package core.entity.actor

import core.enumeration.CreationTypeEnum

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

case class Properties(
  entityId: String = null,
  shardId: String = null,
  timeManager: ActorRef = null,
  creatorManager: ActorRef = null,
  data: Any = null,
  actorType: CreationTypeEnum = LoadBalancedDistributed
)
