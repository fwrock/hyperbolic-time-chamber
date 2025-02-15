package org.interscity.htc
package core.entity.actor

case class PoolDistributedConfiguration(
  roundRobinPool: Int = 5,
  totalInstances: Int = 100,
  maxInstancesPerNode: Int = 10,
  allowLocalRoutes: Boolean = true,
  useRoles: Set[String] = Set("default")
)
