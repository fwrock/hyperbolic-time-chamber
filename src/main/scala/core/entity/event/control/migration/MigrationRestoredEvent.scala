package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

import org.apache.pekko.actor.ActorRef

/** Sent by a simulation actor to the SnapshotManager immediately after it successfully restores its
  * state from a migration snapshot.
  *
  * The SnapshotManager responds with [[MigrationContextEvent]] carrying the full initialization
  * context (timeManagers, reporters) so the actor can complete its bring-up without relying on a
  * CreatorLoadData that may no longer be alive.
  *
  * @param entityId
  *   The ID of the restored entity
  * @param actorRef
  *   Self-reference of the restored actor (for the SnapshotManager to reply to)
  */
case class MigrationRestoredEvent(
  entityId: String,
  actorRef: ActorRef
) extends BaseEvent[DefaultBaseEventData](actorRef = actorRef)
