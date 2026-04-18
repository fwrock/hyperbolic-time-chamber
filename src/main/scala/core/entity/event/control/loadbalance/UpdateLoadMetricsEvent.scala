package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData
import core.entity.control.loadbalance.ShardMetrics

/** Event sent by shards to report their current load metrics to the LoadBalanceManager.
  *
  * @param metrics
  *   Current shard metrics snapshot
  */
case class UpdateLoadMetricsEvent(
  metrics: ShardMetrics
) extends BaseEvent[DefaultBaseEventData]
