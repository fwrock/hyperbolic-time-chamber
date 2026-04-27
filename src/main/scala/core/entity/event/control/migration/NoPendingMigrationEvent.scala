package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Sent by the SnapshotManager to an entity when no migration snapshot exists for it.
  *
  * Received in response to [[QueryMigrationEvent]] when the entity ID is not found in the pending
  * snapshot store. The entity should proceed with normal initialization.
  */
case class NoPendingMigrationEvent() extends BaseEvent[DefaultBaseEventData]()
