package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Internal self-message sent periodically by LoadBalanceManager to collect
  * shard-location and entity-count data from the allocator and feed it into
  * the balancing strategy's kd-tree.
  *
  * Scheduled every 60 seconds (first at 30s after startup) via
  * [[core.actor.manager.loadbalance.LoadBalanceManager.scheduleMetricsCollection]].
  */
case class CollectAndFeedMetricsEvent() extends BaseEvent[DefaultBaseEventData]
