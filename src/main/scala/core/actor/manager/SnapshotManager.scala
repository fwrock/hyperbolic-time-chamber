package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import core.actor.manager.loadbalance.migration.{ MigrationSnapshot, MigrationStateStoreRegistry }
import core.entity.event.control.migration.{ MigrationContextEvent, NoPendingMigrationEvent, QueryMigrationEvent, RegisterMigrationBatchEvent, SaveMigrationSnapshotEvent }
import core.entity.state.DefaultState
import core.enumeration.ReportTypeEnum
import core.util.ManagerConstantsUtil.SNAPSHOT_MANAGER_ACTOR_NAME

import scala.collection.mutable

/** SnapshotManager
  *
  * Cluster-singleton manager that owns migration snapshot storage and acts as the re-initialization
  * authority after shard hand-off.
  *
  * ===Protocol===
  *
  * '''Save phase''' (entity being migrated, source node):
  *   1. LBM sends [[RegisterMigrationBatchEvent]] to register the batch (entityIds + lbmRef). 2.
  *      Each entity sends [[SaveMigrationSnapshotEvent]] with its snapshot.
  *
  * '''Restore phase''' (entity recreated on target node): 3. Entity sends [[QueryMigrationEvent]].
  * 4. SM replies with [[MigrationContextEvent]] if snapshot found, or [[NoPendingMigrationEvent]]
  * if not (fresh entity, not migrated).
  *
  * Snapshots are cleared from the pending map once consumed.
  *
  * Store selection is driven by config:
  *   - `htc.snapshot-manager.migration.state-store = "redis"` → Redis (multi-node safe)
  *   - default → In-Memory (SM-singleton-hosted, zero-overhead, works for all scenarios because the
  *     SM is a singleton accessible from any node via proxy)
  */
class SnapshotManager(timeManager: ActorRef)
    extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = SNAPSHOT_MANAGER_ACTOR_NAME
    ) {

  import SnapshotManager.RegisterSnapshotContextEvent

  private var poolTimeManagers: mutable.Map[String, ActorRef] = mutable.Map.empty
  private var poolReporters: mutable.Map[ReportTypeEnum, ActorRef] = mutable.Map.empty

  private val pendingSnapshots: mutable.Map[String, (MigrationSnapshot, String)] = mutable.Map.empty

  private val batchTracking: mutable.Map[String, (mutable.Set[String], ActorRef)] =
    mutable.Map.empty

  override def onStart(): Unit =
    logInfo("SnapshotManager started")

  override def handleEvent: Receive = {
    case event: RegisterSnapshotContextEvent => handleRegisterContext(event)
    case event: RegisterMigrationBatchEvent  => handleRegisterBatch(event)
    case event: SaveMigrationSnapshotEvent   => handleSaveSnapshot(event)
    case event: QueryMigrationEvent          => handleQueryMigration(event)
    case _: StopSimulationEvent              => handleStop()
  }

  private def handleRegisterContext(event: RegisterSnapshotContextEvent): Unit = {
    poolTimeManagers = event.timeManagers
    poolReporters = event.reporters
    logInfo("SnapshotManager: migration initialization context registered")
  }

  private def handleRegisterBatch(event: RegisterMigrationBatchEvent): Unit = {
    val pending = mutable.Set.empty[String] ++ event.entityIds
    batchTracking.put(event.batchId, (pending, event.lbmRef))
    logInfo(
      s"SnapshotManager: registered batch '${event.batchId}' with ${event.entityIds.size} entities"
    )
  }

  private def handleSaveSnapshot(event: SaveMigrationSnapshotEvent): Unit = {
    pendingSnapshots.put(event.entityId, (event.snapshot, event.batchId))
    logDebug(s"SnapshotManager: stored snapshot for '${event.entityId}' (batch=${event.batchId})")
  }

  private def handleQueryMigration(event: QueryMigrationEvent): Unit =
    pendingSnapshots.remove(event.entityId) match {
      case Some((snapshot, batchId)) =>
        if (poolTimeManagers.isEmpty) {
          logWarn(
            s"QueryMigrationEvent from '${event.entityId}' received before context was registered — " +
              s"replying with empty timeManagers/reporters."
          )
        }
        val lbmRef = batchTracking.get(batchId).map(_._2).orNull
        logInfo(
          s"SnapshotManager: sending MigrationContextEvent to '${event.entityId}' (batch=$batchId)"
        )
        event.actorRef ! MigrationContextEvent(
          timeManagers = poolTimeManagers,
          reporters = poolReporters,
          snapshot = snapshot,
          batchId = batchId,
          lbmRef = lbmRef
        )
        batchTracking.get(batchId).foreach {
          case (pendingIds, lbm) =>
            pendingIds -= event.entityId
            if (pendingIds.isEmpty && lbm != null) {
              batchTracking -= batchId
              logInfo(s"SnapshotManager: all snapshots consumed for batch '$batchId'")
            }
        }

      case None =>
        logDebug(
          s"SnapshotManager: no pending snapshot for '${event.entityId}' — NoPendingMigrationEvent"
        )
        event.actorRef ! NoPendingMigrationEvent()
    }

  private def handleStop(): Unit = {
    val remaining = pendingSnapshots.size
    if (remaining > 0)
      logWarn(s"SnapshotManager stopping with $remaining orphaned snapshots in pending store")
    pendingSnapshots.clear()
    batchTracking.clear()
    MigrationStateStoreRegistry.clear()
    selfDestruct()
  }
}

object SnapshotManager {
  def props(timeManager: ActorRef): Props =
    Props(classOf[SnapshotManager], timeManager)

  /** Sent by SimulationManager once both poolTimeManagers and reporters are available. This injects
    * the migration initialization context (mirrors what CreatorLoadData does via InitializeEvent
    * during initial entity creation).
    */
  case class RegisterSnapshotContextEvent(
    timeManagers: mutable.Map[String, ActorRef],
    reporters: mutable.Map[ReportTypeEnum, ActorRef]
  )
}
