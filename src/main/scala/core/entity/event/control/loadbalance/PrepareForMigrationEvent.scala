package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

import org.apache.pekko.actor.ActorRef

/** Event sent to actors in a shard that is about to be migrated.
  *
  * When the LoadBalanceManager decides to migrate a shard, it sends this event to each
  * entity in the shard BEFORE the hand-off begins. Upon receiving this event, actors
  * build a [[core.actor.manager.loadbalance.migration.MigrationSnapshot]] and send it
  * to the SnapshotManager via [[core.entity.event.control.migration.SaveMigrationSnapshotEvent]],
  * so that it can be restored after re-creation on the target node.
  *
  * Flow:
  *   1. LBM decides to migrate shard S (batch batchId)
  *   2. TimeManager pauses → MigrationSafeEvent
  *   3. LBM sends PrepareForMigrationEvent to entities in shard S
  *   4. Entities build snapshot → send SaveMigrationSnapshotEvent to SnapshotManager
  *   5. LBM opens migration window (MigrationWindowOpenEvent via PubSub)
  *   6. Pekko hand-off begins; actors re-created on target node
  *   7. Actors query SM → receive MigrationContextEvent → restore state
  *   8. Actors ACK restoration → LBM closes migration window
  *
  * @param shardId
  *   The shard being migrated
  * @param targetNode
  *   Address of the target node (for logging)
  * @param batchId
  *   Migration batch identifier (correlates snapshot saves with this migration wave)
  * @param lbmRef
  *   ActorRef of the LoadBalanceManager singleton proxy (for MigrationRestoredAckEvent routing)
  */
case class PrepareForMigrationEvent(
  shardId: String,
  targetNode: String = "",
  batchId: String = "",
  lbmRef: ActorRef = null
) extends BaseEvent[DefaultBaseEventData]
