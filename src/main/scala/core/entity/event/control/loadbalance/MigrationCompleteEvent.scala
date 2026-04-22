package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Event indicating that a shard migration has completed successfully.
  *
  * Sent to TimeManager to release buffered messages and resume normal operation.
  *
  * @param shardId
  *   The shard that was migrated
  * @param success
  *   Whether the migration was successful
  * @param durationMs
  *   Actual duration in milliseconds
  */
case class MigrationCompleteEvent(
  shardId: String,
  success: Boolean = true,
  durationMs: Long = 0L
) extends BaseEvent[DefaultBaseEventData]
