package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Internal self-message scheduled 200ms after entity snapshot notifications to trigger the
  * distributed migration-window-open broadcast.
  *
  * Sent by [[core.actor.manager.loadbalance.LoadBalanceManager]] to its own singleton proxy after
  * all [[PrepareForMigrationEvent]] messages have been dispatched, giving snapshots time to reach
  * the SnapshotManager before the window opens.
  *
  * @param batchId
  *   The migration batch identifier
  * @param entityIds
  *   The full set of entity IDs participating in this migration batch
  */
case class TriggerWindowOpenEvent(
  batchId: String,
  entityIds: Set[String]
) extends BaseEvent[DefaultBaseEventData]
