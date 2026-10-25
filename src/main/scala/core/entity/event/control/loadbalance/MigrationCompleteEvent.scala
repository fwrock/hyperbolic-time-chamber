package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Correlated internal completion after placement observation and accepted restoration.
  * The manager still waits for window-close ACKs before notifying TimeManager.
  *
  * @param shardId
  *   The shard that was migrated
  * @param success
  *   Whether the migration was successful
  * @param durationMs
  *   Handoff-monitor-to-confirmation duration in milliseconds, excluding earlier preparation
  * @param batchId
  *   Wave identity; empty for an explicitly skipped static plan
  * @param attemptId
  *   Fresh identity for this admission's handoff/skip completion
  */
case class MigrationCompleteEvent(
  shardId: String,
  success: Boolean = true,
  durationMs: Long = 0L,
  batchId: String = "",
  attemptId: String = ""
) extends BaseEvent[DefaultBaseEventData]
