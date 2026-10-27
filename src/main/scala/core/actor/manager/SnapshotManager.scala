package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import core.actor.manager.loadbalance.migration.{ MigrationSnapshot, MigrationStateStoreRegistry }
import core.entity.event.control.migration.*
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
  *   1. LBM registers the batch and awaits [[MigrationBatchRegisteredEvent]].
  *   2. Each frozen source sends [[SaveMigrationSnapshotEvent]] with its snapshot.
  *   3. [[MigrationSnapshotsReadyEvent]] acknowledges the complete expected saved set.
  *   4. At handoff, a source drains its retained inputs and sends [[SaveFinalMigrationSnapshotEvent]].
  *      [[FinalMigrationSnapshotSavedEvent]] authorizes source termination only after final storage.
  *
  * '''Restore phase''' (entity recreated on target node): Entity sends [[QueryMigrationEvent]].
  * SM replies with [[MigrationContextEvent]] if snapshot found, or [[NoPendingMigrationEvent]]
  * if not (fresh entity, not migrated).
  *
  * Snapshots remain until the offered target confirms successful application. Lightweight receipts
  * prevent duplicate queries from falling back to fresh initialization after confirmation.
  *
  * The active store is singleton-hosted memory; the separate Redis store is not connected here.
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

  private case class Batch(
    expected: Set[String],
    pendingSaves: mutable.Set[String],
    pendingRestores: mutable.Set[String],
    lbmRef: ActorRef,
    var ready: Boolean = false
  )
  private val batchTracking = mutable.Map.empty[String, Batch]
  private val finalRevisions = mutable.Map.empty[(String, String), Long]
  private case class RestoreOffer(target: ActorRef, token: String, revision: Long, var failed: Boolean = false)
  private case class RestoreReceipt(batchId: String, offer: RestoreOffer, lbmRef: ActorRef)
  private val restoreOffers = mutable.Map.empty[String, RestoreOffer]
  private val completedRestores = mutable.Map.empty[String, RestoreReceipt]

  override def onStart(): Unit =
    logInfo("SnapshotManager started")

  override def handleEvent: Receive = {
    case event: RegisterSnapshotContextEvent => handleRegisterContext(event)
    case event: RegisterMigrationBatchEvent  => handleRegisterBatch(event)
    case event: SaveMigrationSnapshotEvent   => handleSaveSnapshot(event)
    case event: SaveFinalMigrationSnapshotEvent => handleSaveFinalSnapshot(event)
    case event: QueryMigrationEvent          => handleQueryMigration(event)
    case event: MigrationRestoredAckEvent     => handleRestoreAck(event)
    case event: MigrationRestoreFailedEvent   => handleRestoreFailed(event)
    case _: StopSimulationEvent              => handleStop()
  }

  private def handleRegisterContext(event: RegisterSnapshotContextEvent): Unit = {
    poolTimeManagers = event.timeManagers
    poolReporters = event.reporters
    logInfo("SnapshotManager: migration initialization context registered")
  }

  private def handleRegisterBatch(event: RegisterMigrationBatchEvent): Unit = {
    val conflict = batchTracking.exists { case (id, batch) =>
      id != event.batchId && batch.expected.exists(event.entityIds.contains)
    }
    val alreadyCompleted = event.entityIds.exists(id =>
      completedRestores.get(id).exists(_.batchId == event.batchId))
    if (event.batchId.isEmpty || event.lbmRef == null || conflict || alreadyCompleted) {
      Option(event.lbmRef).foreach(_ ! MigrationPreparationFailedEvent(event.batchId, "", "invalid or overlapping migration batch"))
      return
    }
    batchTracking.get(event.batchId) match {
      case Some(existing) if existing.expected != event.entityIds || existing.lbmRef != event.lbmRef =>
        event.lbmRef ! MigrationPreparationFailedEvent(event.batchId, "", "conflicting migration batch registration")
      case existing =>
        val batch = existing.getOrElse {
          val created = Batch(event.entityIds, mutable.Set.empty ++ event.entityIds,
            mutable.Set.empty ++ event.entityIds, event.lbmRef)
          batchTracking(event.batchId) = created
          created
        }
        event.lbmRef ! MigrationBatchRegisteredEvent(event.batchId)
        acknowledgeReady(event.batchId, batch)
    }
  }

  private def handleSaveSnapshot(event: SaveMigrationSnapshotEvent): Unit = {
    if (completedRestores.get(event.entityId).exists(_.batchId == event.batchId)) return
    val batch = batchTracking.get(event.batchId)
    val conflict = pendingSnapshots.get(event.entityId).exists { case (snapshot, id) =>
      id != event.batchId || snapshot != event.snapshot
    }
    if (!batch.exists(_.pendingRestores.contains(event.entityId)) || event.snapshot == null ||
        event.snapshot.entityId != event.entityId || conflict) {
      val failed = MigrationPreparationFailedEvent(event.batchId, event.entityId, "unregistered, mismatched or conflicting migration snapshot")
      batch.map(_.lbmRef).getOrElse(sender()) ! failed
      return
    }
    pendingSnapshots.put(event.entityId, (event.snapshot, event.batchId))
    batch.get.pendingSaves -= event.entityId
    acknowledgeReady(event.batchId, batch.get)
    logDebug(s"SnapshotManager: stored snapshot for '${event.entityId}' (batch=${event.batchId})")
  }

  private def acknowledgeReady(batchId: String, batch: Batch): Unit =
    if (batch.pendingSaves.isEmpty && !batch.ready) {
      batch.ready = true
      batch.lbmRef ! MigrationSnapshotsReadyEvent(batchId, batch.expected)
    }

  private def handleSaveFinalSnapshot(event: SaveFinalMigrationSnapshotEvent): Unit = {
    val key = event.batchId -> event.entityId
    val currentRevision = finalRevisions.getOrElse(key, 0L)
    val batch = batchTracking.get(event.batchId)
    val valid = batch.exists(b => b.ready && b.pendingRestores.contains(event.entityId)) &&
      event.snapshot != null && event.snapshot.entityId == event.entityId && event.revision > 0L &&
      (!restoreOffers.contains(event.entityId) || event.revision <= currentRevision)
    val conflicting = event.revision == currentRevision &&
      !pendingSnapshots.get(event.entityId).contains(event.snapshot -> event.batchId)
    if (!valid || event.revision > currentRevision + 1L || conflicting) {
      val failed = MigrationPreparationFailedEvent(
        event.batchId, event.entityId, "invalid or conflicting final migration snapshot")
      batch.foreach(_.lbmRef ! failed)
      sender() ! failed
    } else {
      if (event.revision > currentRevision) {
        pendingSnapshots(event.entityId) = event.snapshot -> event.batchId
        finalRevisions(key) = event.revision
      }
      sender() ! FinalMigrationSnapshotSavedEvent(event.entityId, event.batchId, event.revision)
    }
  }

  private def handleQueryMigration(event: QueryMigrationEvent): Unit = {
    completedRestores.get(event.entityId).filter(_.offer.target == event.actorRef) match {
      case Some(receipt) =>
        if (receipt.offer.failed) {
          val failed = MigrationPreparationFailedEvent(receipt.batchId, event.entityId, "completed target reported lost restore state")
          event.actorRef ! failed
          receipt.lbmRef ! failed
          return
        }
        event.actorRef ! MigrationRestoreAlreadyAcceptedEvent(event.entityId, receipt.batchId,
          receipt.offer.token, receipt.offer.revision)
        receipt.lbmRef ! MigrationRestoreAcceptedEvent(event.entityId, receipt.batchId)
        return
      case None => ()
    }
    val batchWithoutFinalCut = batchTracking.find { case (batchId, batch) =>
      batch.pendingRestores.contains(event.entityId) && !finalRevisions.contains(batchId -> event.entityId)
    }
    if (batchWithoutFinalCut.nonEmpty) {
      val (batchId, batch) = batchWithoutFinalCut.get
      val failed = MigrationPreparationFailedEvent(batchId, event.entityId,
        "target queried before the source's final snapshot was accepted")
      batch.lbmRef ! failed
      event.actorRef ! failed
      return
    }
    pendingSnapshots.get(event.entityId) match {
      case Some((snapshot, batchId)) =>
        val batch = batchTracking(batchId)
        val offer = restoreOffers.getOrElseUpdate(event.entityId,
          RestoreOffer(event.actorRef, java.util.UUID.randomUUID().toString, finalRevisions(batchId -> event.entityId)))
        if (offer.target != event.actorRef || offer.failed) {
          val failed = MigrationPreparationFailedEvent(batchId, event.entityId,
            "restore target changed or its previous restore failed; independent recovery is unsupported")
          batch.lbmRef ! failed
          event.actorRef ! failed
          return
        }
        if (poolTimeManagers.isEmpty) {
          logWarn(
            s"QueryMigrationEvent from '${event.entityId}' received before context was registered — " +
              s"replying with empty timeManagers/reporters."
          )
        }
        val lbmRef = batchTracking.get(batchId).map(_.lbmRef).orNull
        logInfo(
          s"SnapshotManager: sending MigrationContextEvent to '${event.entityId}' (batch=$batchId)"
        )
        event.actorRef ! MigrationContextEvent(
          timeManagers = poolTimeManagers,
          reporters = poolReporters,
          snapshot = snapshot,
          batchId = batchId,
          lbmRef = lbmRef,
          snapshotManagerRef = self,
          restoreToken = offer.token,
          snapshotRevision = offer.revision
        )

      case None =>
        completedRestores.get(event.entityId) match {
          case Some(receipt) =>
            val failed = MigrationPreparationFailedEvent(receipt.batchId, event.entityId,
              "confirmed migration target restarted; independent recovery is unsupported")
            receipt.lbmRef ! failed
            event.actorRef ! failed
          case None => event.actorRef ! NoPendingMigrationEvent()
        }
    }
  }

  private def matchesOffer(entityId: String, batchId: String, token: String, revision: Long): Boolean =
    pendingSnapshots.get(entityId).exists(_._2 == batchId) &&
      finalRevisions.get(batchId -> entityId).contains(revision) &&
      restoreOffers.get(entityId).exists(o => !o.failed && o.target == sender() &&
        o.token == token && o.revision == revision)

  private def handleRestoreAck(event: MigrationRestoredAckEvent): Unit = {
    if (matchesOffer(event.entityId, event.batchId, event.restoreToken, event.snapshotRevision)) {
      val batch = batchTracking(event.batchId)
      completedRestores(event.entityId) = RestoreReceipt(event.batchId, restoreOffers(event.entityId), batch.lbmRef)
      pendingSnapshots -= event.entityId
      restoreOffers -= event.entityId
      finalRevisions -= (event.batchId -> event.entityId)
      batch.pendingRestores -= event.entityId
      if (batch.pendingRestores.isEmpty) batchTracking -= event.batchId
      batch.lbmRef ! MigrationRestoreAcceptedEvent(event.entityId, event.batchId)
    } else completedRestores.get(event.entityId).filter(r => !r.offer.failed && r.batchId == event.batchId &&
      r.offer.target == sender() && r.offer.token == event.restoreToken &&
      r.offer.revision == event.snapshotRevision).foreach { receipt =>
      receipt.lbmRef ! MigrationRestoreAcceptedEvent(event.entityId, event.batchId)
    }
  }

  private def handleRestoreFailed(event: MigrationRestoreFailedEvent): Unit =
    if (matchesOffer(event.entityId, event.batchId, event.restoreToken, event.snapshotRevision)) {
      restoreOffers(event.entityId).failed = true
      batchTracking(event.batchId).lbmRef ! MigrationPreparationFailedEvent(event.batchId, event.entityId,
        s"target restore failed: ${event.reason}")
    } else completedRestores.get(event.entityId).filter(r => r.batchId == event.batchId &&
      r.offer.target == sender() && r.offer.token == event.restoreToken &&
      r.offer.revision == event.snapshotRevision).foreach { receipt =>
      receipt.offer.failed = true
      receipt.lbmRef ! MigrationPreparationFailedEvent(event.batchId, event.entityId, event.reason)
    }

  private def handleStop(): Unit = {
    val remaining = pendingSnapshots.size
    if (remaining > 0)
      logWarn(s"SnapshotManager stopping with $remaining orphaned snapshots in pending store")
    pendingSnapshots.clear()
    batchTracking.clear()
    finalRevisions.clear()
    restoreOffers.clear()
    completedRestores.clear()
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
