package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

import org.apache.pekko.actor.ActorRef

/** Sent by a simulation entity to the SnapshotManager to request its migration snapshot.
  *
  * Emitted in preStart() when the migration window flag is active (isMigrationActive = true).
  * The entity stashes all incoming messages and waits for either:
  *   - [[MigrationContextEvent]] — snapshot found, entity restores and joins
  *   - [[NoPendingMigrationEvent]] — no snapshot found, entity does normal initialization
  *
  * @param entityId
  *   The ID of the entity requesting its snapshot
  * @param actorRef
  *   Self-reference of the querying entity (for the SnapshotManager to reply to)
  */
case class QueryMigrationEvent(
  entityId: String,
  actorRef: ActorRef
) extends BaseEvent[DefaultBaseEventData]()
