package org.interscity.htc
package core.entity.actor

case class PoolDistributedConfiguration(
  roundRobinPool: Int = 0,
  totalInstances: Int = 100,
  maxInstancesPerNode: Int = 10,
  allowLocalRoutes: Boolean = true,
  useRoles: Set[String] = null
)
