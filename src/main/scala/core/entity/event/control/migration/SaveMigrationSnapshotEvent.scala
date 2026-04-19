package org.interscity.htc
package core.entity.event.control.migration

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Sent by a simulation entity to the SnapshotManager to persist its migration snapshot.
  *
  * The entity sends this event BEFORE Pekko hands off the shard, while the actor is
  * still alive and has valid state. The SnapshotManager stores the snapshot keyed by
  * entityId and associates it with the active migration batch.
  *
  * @param entityId
  *   The ID of the entity being migrated
  * @param batchId
  *   The migration batch ID (provided in PrepareForMigrationEvent)
  * @param snapshot
  *   The fully serialized migration snapshot
  */
case class SaveMigrationSnapshotEvent(
  entityId: String,
  batchId: String,
  snapshot: MigrationSnapshot
) extends BaseEvent[DefaultBaseEventData]()
