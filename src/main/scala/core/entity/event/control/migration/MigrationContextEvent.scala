package org.interscity.htc
package core.entity.event.control.migration

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData
import core.enumeration.ReportTypeEnum

import org.apache.pekko.actor.ActorRef

import scala.collection.mutable

/** Sent by the SnapshotManager to a restored actor in response to [[QueryMigrationEvent]].
  *
  * Carries the full initialization context (timeManagers, reporters) AND the migration snapshot so
  * the entity can restore its domain state and simulation metadata in a single message. Also
  * carries a store-issued restore token and revision so the entity can confirm application to SM.
  *
  * @param timeManagers
  *   Map of time manager type → ActorRef (cluster-transparent)
  * @param reporters
  *   Map of report type → ActorRef (cluster-transparent)
  * @param snapshot
  *   The entity's migration snapshot (state, tick, lamport, relationships…)
  * @param batchId
  *   The migration batch ID — forwarded to [[MigrationRestoredAckEvent]]
  * @param lbmRef
  *   ActorRef of the LoadBalanceManager singleton proxy for failure notification
  * @param snapshotManagerRef
  *   Store that must accept the target's confirmation before releasing state
  * @param restoreToken
  *   Store-issued identity of this target's restore offer
  * @param snapshotRevision
  *   Final source revision covered by this offer
  */
case class MigrationContextEvent(
  timeManagers: mutable.Map[String, ActorRef],
  reporters: mutable.Map[ReportTypeEnum, ActorRef],
  snapshot: MigrationSnapshot,
  batchId: String,
  lbmRef: ActorRef,
  snapshotManagerRef: ActorRef = null,
  restoreToken: String = "",
  snapshotRevision: Long = 0L
) extends BaseEvent[DefaultBaseEventData]()
