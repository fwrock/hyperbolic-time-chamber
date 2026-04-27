package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

import org.apache.pekko.actor.ActorRef

/** Broadcast by the LoadBalanceManager via DistributedPubSub to open the migration window.
  *
  * Every [[core.actor.manager.loadbalance.migration.MigrationWindowSubscriber]] on all cluster
  * nodes receives this event and:
  *   1. Sets MigrationStateStoreRegistry.isMigrationActive = true 2. Acknowledges via
  *      [[MigrationWindowAckEvent]] with phase "open"
  *
  * The LBM waits for ACKs from all known cluster nodes before triggering the Pekko shard rebalance
  * — ensuring the flag is set everywhere BEFORE any actor is recreated.
  *
  * @param batchId
  *   Unique ID for this migration batch (correlates all events in this migration cycle)
  * @param entityIds
  *   IDs of all entities being migrated in this batch
  * @param lbmRef
  *   ActorRef of the LoadBalanceManager singleton proxy (for ACK routing)
  */
case class MigrationWindowOpenEvent(
  batchId: String,
  entityIds: Set[String],
  lbmRef: ActorRef
) extends BaseEvent[DefaultBaseEventData]()
