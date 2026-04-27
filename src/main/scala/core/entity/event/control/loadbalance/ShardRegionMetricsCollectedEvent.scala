package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Internal self-message sent back to the LoadBalanceManager after a
  * `ShardRegion.GetShardRegionState` ask resolves successfully for one shard region type.
  *
  * This decouples the async Future callback (which runs on the global EC) from the actor's
  * message-processing loop: the Future records shard locations directly and then posts this
  * lightweight ack back to the LBM actor thread for logging.
  *
  * @param typeName
  *   The shard region type name (e.g. "org.interscity.htc.model.Person")
  * @param shardCount
  *   The number of shards found in this region on the local node
  */
case class ShardRegionMetricsCollectedEvent(
  typeName: String,
  shardCount: Int
) extends BaseEvent[DefaultBaseEventData]
