package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData
import core.actor.manager.loadbalance.migration.MigrationSnapshot

case class MigrationBatchRegisteredEvent(batchId: String) extends BaseEvent[DefaultBaseEventData]

case class MigrationSnapshotsReadyEvent(batchId: String, entityIds: Set[String])
    extends BaseEvent[DefaultBaseEventData]

case class MigrationPreparationFailedEvent(batchId: String, entityId: String, reason: String)
    extends BaseEvent[DefaultBaseEventData]

case class AbortMigrationPreparationEvent(batchId: String) extends BaseEvent[DefaultBaseEventData]

case class SaveFinalMigrationSnapshotEvent(entityId: String, batchId: String,
  snapshot: MigrationSnapshot, revision: Long) extends BaseEvent[DefaultBaseEventData]

case class FinalMigrationSnapshotSavedEvent(entityId: String, batchId: String, revision: Long)
    extends BaseEvent[DefaultBaseEventData]

/** Shard handoff drains the frozen source tail and saves its final cut before termination. */
case class MigrationHandoffStopEvent() extends BaseEvent[DefaultBaseEventData]
