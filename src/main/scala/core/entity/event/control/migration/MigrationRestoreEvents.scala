package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

case class MigrationRestoreAcceptedEvent(entityId: String, batchId: String)
    extends BaseEvent[DefaultBaseEventData]

case class MigrationRestoreAlreadyAcceptedEvent(entityId: String, batchId: String,
  restoreToken: String, snapshotRevision: Long) extends BaseEvent[DefaultBaseEventData]

case class MigrationRestoreFailedEvent(entityId: String, batchId: String,
  restoreToken: String, snapshotRevision: Long, reason: String) extends BaseEvent[DefaultBaseEventData]
