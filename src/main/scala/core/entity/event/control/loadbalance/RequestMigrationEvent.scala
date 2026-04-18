package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData
import core.entity.control.loadbalance.MigrationPlan

/** Event requesting the LoadBalanceManager to execute a shard migration.
  *
  * @param plan
  *   The migration plan to execute
  */
case class RequestMigrationEvent(
  plan: MigrationPlan
) extends BaseEvent[DefaultBaseEventData]
