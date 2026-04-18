package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Event sent to actors in a shard that is about to be migrated.
  *
  * When the LoadBalanceManager decides to migrate a shard, it sends this event to each
  * entity in the shard BEFORE the hand-off begins. Upon receiving this event, actors
  * serialize their current state to the [[core.actor.manager.loadbalance.migration.MigrationStateStore]]
  * so that it can be restored after re-creation on the target node.
  *
  * Flow:
  *   1. LBM decides to migrate shard S
  *   2. TimeManager pauses → MigrationSafeEvent
  *   3. LBM sends PrepareForMigrationEvent to entities in shard S
  *   4. Entities serialize state → MigrationStateStore (Redis/in-memory)
  *   5. Pekko hand-off begins (DestructEvent stops actors)
  *   6. Actors re-created on target node, restore state from store
  *
  * @param shardId
  *   The shard being migrated
  * @param targetNode
  *   Address of the target node (for logging)
  */
case class PrepareForMigrationEvent(
  shardId: String,
  targetNode: String = ""
) extends BaseEvent[DefaultBaseEventData]
