package org.interscity.htc
package core.entity.actor

import core.enumeration.CreationTypeEnum

import org.interscity.htc.core.enumeration.CreationTypeEnum.Simple

case class ActorSimulation(
  id: String,
  name: String,
  typeActor: String,
  creationType: CreationTypeEnum = Simple,
  count: Int = 1,
  poolConfiguration: PoolDistributedConfiguration = PoolDistributedConfiguration(),
  data: ActorDataSimulation,
  dependencies: Map[String, String] = null
)
