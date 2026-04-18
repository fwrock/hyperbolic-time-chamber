package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Event indicating that a shard migration has started.
  *
  * Sent to the TimeManager so it knows to buffer messages for this shard.
  *
  * @param shardId
  *   The shard being migrated
  * @param estimatedDurationMs
  *   Estimated migration duration in milliseconds
  */
case class MigrationStartEvent(
  shardId: String,
  estimatedDurationMs: Long = 100L
) extends BaseEvent[DefaultBaseEventData]
