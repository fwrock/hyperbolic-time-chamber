package org.interscity.htc
package core.actor.manager.loadbalance.migration

import core.entity.control.loadbalance.MigrationPlan

import org.apache.pekko.actor.{ ActorRef, Address }

import scala.collection.mutable

/** Coordinates shard migration between cluster nodes.
  *
  * Responsibilities:
  *   - Track active migrations (only one per shard at a time)
  *   - Coordinate with TimeManager for safe migration windows
  *   - Track migration statistics
  *
  * The coordinator does NOT directly move shards — it orchestrates the process: 1. Announce
  * migration start 2. Signal Pekko shard hand-off 3. Wait for re-hydration confirmation
  * 4. Announce migration complete
  *
  * In-flight-message safety during the actual hand-off is provided elsewhere, not by this class:
  * `LoadBalanceManager`'s distributed migration-window protocol pauses the TimeManager at a safe
  * tick boundary before migrating (no spontaneous events in flight), and
  * `SimulationBaseActor.preStart`'s `awaitingMigration`/`stash()`/`unstashAll()` handling covers
  * messages arriving at the re-hydrating entity on the target node before its snapshot restore
  * completes (see `MigrationWindowSubscriber`/`MigrationStateStoreRegistry.isMigrationActive`).
  * A previous `MessageBuffer` class here (`buffer`/`drainBuffer`/etc.) was never actually wired
  * into the message path — `buffer()`, the only method that would enqueue a real message, had no
  * callers anywhere in the codebase — so it provided no protection despite the docs/KNOWN_GAPS.md
  * write-up describing it as a lossy-at-capacity safety net. Removed 2026-08-05 as dead code,
  * not a working mechanism that regressed; see docs/KNOWN_GAPS.md's Gap C entry for the real
  * remaining risk this investigation found (a lost-update window between a source entity's
  * `PrepareForMigrationEvent` snapshot and its actual Pekko-triggered stop), which is a distinct,
  * still-open architectural question this removal does not address.
  */
class ShardMigrationCoordinator(
  val maxConcurrentMigrations: Int = 3
) {

  /** Currently executing migrations */
  private val activeMigrations: mutable.Map[String, MigrationState] = mutable.Map.empty

  /** Migration queue for when we exceed concurrent limit */
  private val pendingMigrations: mutable.Queue[MigrationPlan] = mutable.Queue.empty

  /** Completed migration count */
  private var completedCount: Long = 0L
  private var failedCount: Long = 0L
  private var totalMigrationTimeNanos: Long = 0L

  /** Attempts to start a migration. Returns true if started, false if queued or rejected. */
  def requestMigration(plan: MigrationPlan): MigrationRequestResult =
    if (activeMigrations.contains(plan.shardId)) {
      MigrationRequestResult.AlreadyMigrating
    } else if (activeMigrations.size >= maxConcurrentMigrations) {
      pendingMigrations.enqueue(plan)
      MigrationRequestResult.Queued(pendingMigrations.size)
    } else {
      startMigration(plan)
      MigrationRequestResult.Started
    }

  /** Marks a migration as complete. Returns the next pending migration if available. */
  def completeMigration(shardId: String, success: Boolean): Option[MigrationPlan] = {
    activeMigrations.remove(shardId).foreach {
      state =>
        val duration = System.nanoTime() - state.startedAt
        totalMigrationTimeNanos += duration
        if (success) completedCount += 1
        else failedCount += 1
    }

    // Start next pending migration if any
    if (pendingMigrations.nonEmpty && activeMigrations.size < maxConcurrentMigrations) {
      val next = pendingMigrations.dequeue()
      startMigration(next)
      Some(next)
    } else None
  }

  /** Gets the current state of a migration. */
  def getMigrationState(shardId: String): Option[MigrationState] =
    activeMigrations.get(shardId)

  /** Checks if a shard is currently being migrated. */
  def isMigrating(shardId: String): Boolean =
    activeMigrations.contains(shardId)

  /** Gets the number of active migrations. */
  def activeMigrationCount: Int = activeMigrations.size

  /** Gets the number of pending migrations. */
  def pendingMigrationCount: Int = pendingMigrations.size

  /** Gets migration statistics. */
  def getStats: MigrationStats = MigrationStats(
    activeMigrations = activeMigrations.size,
    pendingMigrations = pendingMigrations.size,
    completedMigrations = completedCount,
    failedMigrations = failedCount,
    avgMigrationTimeMs =
      if (completedCount > 0) (totalMigrationTimeNanos / completedCount / 1e6).toLong
      else 0L
  )

  /** Aborts all active and pending migrations. */
  def abortAll(): Unit = {
    activeMigrations.clear()
    pendingMigrations.clear()
  }

  // ── Internal ───────────────────────────────────────────────────────────────

  private def startMigration(plan: MigrationPlan): Unit = {
    val state = MigrationState(
      plan = plan,
      startedAt = System.nanoTime(),
      phase = MigrationPhase.Preparing
    )
    activeMigrations.put(plan.shardId, state)
  }
}

/** State of an active shard migration. */
case class MigrationState(
  plan: MigrationPlan,
  startedAt: Long,
  phase: MigrationPhase
)

/** Phases of a shard migration. */
enum MigrationPhase:
  case Preparing
  case Serializing
  case Transferring
  case Rehydrating
  case Completing

/** Result of a migration request. */
enum MigrationRequestResult:
  case Started
  case Queued(position: Int)
  case AlreadyMigrating
  case Rejected(reason: String)

/** Aggregate migration statistics. */
case class MigrationStats(
  activeMigrations: Int,
  pendingMigrations: Int,
  completedMigrations: Long,
  failedMigrations: Long,
  avgMigrationTimeMs: Long
)
