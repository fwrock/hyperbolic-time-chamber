package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

import org.apache.pekko.actor.ActorRef

/** Sent by the SnapshotManager to register a migration batch.
  *
  * The LoadBalanceManager sends this to SM before notifying entities, so SM knows
  * which entities belong to the batch and can associate their snapshots with the
  * correct batchId and lbmRef.
  *
  * @param batchId
  *   Unique ID for this migration batch
  * @param entityIds
  *   IDs of all entities being migrated
  * @param lbmRef
  *   ActorRef of the LBM singleton proxy (for entity-to-LBM ACK routing)
  */
case class RegisterMigrationBatchEvent(
  batchId: String,
  entityIds: Set[String],
  lbmRef: ActorRef
) extends BaseEvent[DefaultBaseEventData]()
