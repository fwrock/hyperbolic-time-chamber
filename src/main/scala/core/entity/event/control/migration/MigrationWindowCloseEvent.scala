package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

import org.apache.pekko.actor.ActorRef

/** Broadcast by the LoadBalanceManager via DistributedPubSub to close the migration window.
  *
  * Sent after all entities in the migration batch have been successfully restored on their
  * target nodes (all [[MigrationRestoredAckEvent]] received by LBM).
  *
  * Every [[core.actor.manager.loadbalance.migration.MigrationWindowSubscriber]] on all
  * cluster nodes receives this event and:
  *   1. Sets MigrationStateStoreRegistry.isMigrationActive = false
  *   2. Acknowledges via [[MigrationWindowAckEvent]] with phase "close"
  *
  * @param batchId
  *   Unique ID for this migration batch
  * @param lbmRef
  *   ActorRef of the LoadBalanceManager singleton proxy (for ACK routing)
  */
case class MigrationWindowCloseEvent(
  batchId: String,
  lbmRef: ActorRef
) extends BaseEvent[DefaultBaseEventData]()
