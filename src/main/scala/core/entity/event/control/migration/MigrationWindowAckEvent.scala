package org.interscity.htc
package core.entity.event.control.migration

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Sent by a MigrationWindowSubscriber to the LoadBalanceManager to acknowledge a migration window
  * open or close broadcast.
  *
  * The LBM uses these to implement the two-phase distributed barrier:
  *   - "open" ACKs: wait for all nodes → then trigger Pekko rebalance
  *   - "close" ACKs: wait for all nodes → then notify TimeManager to resume
  *
  * @param batchId
  *   The migration batch ID (correlates with the open/close event)
  * @param phase
  *   Either "open" or "close"
  * @param nodeAddress
  *   String representation of the sending node's Pekko address (for deduplication)
  */
case class MigrationWindowAckEvent(
  batchId: String,
  phase: String,
  nodeAddress: String
) extends BaseEvent[DefaultBaseEventData]()
