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
  *   - Manage message buffering during transit
  *   - Track migration statistics
  *
  * The coordinator does NOT directly move shards — it orchestrates the process: 1. Announce
  * migration start (triggers buffering) 2. Signal Pekko shard hand-off 3. Wait for re-hydration
  * confirmation 4. Release buffered messages 5. Announce migration complete
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

  /** Message buffer shared across all migrations */
  val messageBuffer: MessageBuffer = new MessageBuffer()

  /** Attempts to start a migration. Returns true if started, false if queued or rejected. */
  def requestMigration(plan: MigrationPlan): MigrationRequestResult = {
    if (activeMigrations.contains(plan.shardId)) {
      MigrationRequestResult.AlreadyMigrating
    } else if (activeMigrations.size >= maxConcurrentMigrations) {
      pendingMigrations.enqueue(plan)
      MigrationRequestResult.Queued(pendingMigrations.size)
    } else {
      startMigration(plan)
      MigrationRequestResult.Started
    }
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

    // Release buffered messages
    messageBuffer.drainBuffer(shardId)

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
      else 0L,
    totalBufferedMessages = messageBuffer.totalBufferedCount
  )

  /** Aborts all active and pending migrations. */
  def abortAll(): Unit = {
    activeMigrations.keys.foreach(messageBuffer.abortBuffering)
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
    messageBuffer.startBuffering(plan.shardId)
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
  case Preparing    // Coordinating with TimeManager
  case Serializing  // Compacting shard state
  case Transferring // State in transit
  case Rehydrating  // Rebuilding on target node
  case Completing   // Releasing buffers, finalizing

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
  avgMigrationTimeMs: Long,
  totalBufferedMessages: Int
)
