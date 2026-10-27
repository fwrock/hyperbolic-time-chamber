package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Sent by a simulation entity to SnapshotManager after fully restoring its state from a
  * migration snapshot.
  *
  * Emitted after [[MigrationContextEvent]] is processed — state and context have been applied, time
  * manager registration is done, and the entity is operational.
  *
  * SM validates the target incarnation, token and final revision before releasing the snapshot
  * and notifying LBM with [[MigrationRestoreAcceptedEvent]].
  *
  * @param entityId
  *   The ID of the restored entity
  * @param batchId
  *   The migration batch ID (for LBM bookkeeping)
  * @param restoreToken
  *   Token from the offered MigrationContextEvent
  * @param snapshotRevision
  *   Final source revision that was applied
  */
case class MigrationRestoredAckEvent(
  entityId: String,
  batchId: String,
  restoreToken: String = "",
  snapshotRevision: Long = 0L
) extends BaseEvent[DefaultBaseEventData]()
