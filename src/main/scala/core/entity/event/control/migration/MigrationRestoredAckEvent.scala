package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Sent by a simulation entity to the LoadBalanceManager after fully restoring its
  * state from a migration snapshot.
  *
  * Emitted after [[MigrationContextEvent]] is processed — state and context have been
  * applied, time manager registration is done, and the entity is operational.
  *
  * The LBM counts these ACKs. When all entities in the batch have sent their ACK,
  * the LBM broadcasts [[MigrationWindowCloseEvent]] to close the window.
  *
  * @param entityId
  *   The ID of the restored entity
  * @param batchId
  *   The migration batch ID (for LBM bookkeeping)
  */
case class MigrationRestoredAckEvent(
  entityId: String,
  batchId: String
) extends BaseEvent[DefaultBaseEventData]()
