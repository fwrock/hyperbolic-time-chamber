package org.interscity.htc
package core.actor.manager.loadbalance.migration

import org.apache.pekko.actor.ActorRef

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

/** Global registry for the migration window state and SnapshotManager proxy ref.
  *
  * Provides JVM-local, thread-safe access to:
  *   - The active [[MigrationStateStore]] (legacy; kept for Redis option)
  *   - The SnapshotManager cluster-singleton proxy (set by [[core.actor.manager.loadbalance.migration.MigrationWindowSubscriber]])
  *   - The migration window flag (`isMigrationActive`) — toggled by the subscriber
  *     when it receives [[core.entity.event.control.migration.MigrationWindowOpenEvent]] /
  *     [[core.entity.event.control.migration.MigrationWindowCloseEvent]] from the LBM
  *
  * Thread-safe via [[AtomicReference]] and [[AtomicBoolean]].
  */
object MigrationStateStoreRegistry {

  private val store: AtomicReference[MigrationStateStore] =
    new AtomicReference[MigrationStateStore](null)

  /** SnapshotManager cluster-singleton proxy — set by MigrationWindowSubscriber.onStart()
    * on every node. Used by entities to send QueryMigrationEvent / SaveMigrationSnapshotEvent. */
  private val snapshotManager: AtomicReference[ActorRef] =
    new AtomicReference[ActorRef](null)

  /** Migration window flag — true while a shard migration is in progress.
    *
    * Set to true by [[core.actor.manager.loadbalance.migration.MigrationWindowSubscriber]] when
    * it receives MigrationWindowOpenEvent. Reset to false on MigrationWindowCloseEvent.
    *
    * Entities check this in preStart() to decide whether to query the SnapshotManager
    * for a pending migration snapshot. When false the check is a single volatile read
    * with zero overhead.
    */
  val isMigrationActive: AtomicBoolean = new AtomicBoolean(false)

  /** Registers the migration state store to be used during shard hand-offs. */
  def register(stateStore: MigrationStateStore): Unit =
    store.set(stateStore)

  /** Registers the SnapshotManager actor reference (proxy). Called by MigrationWindowSubscriber. */
  def registerSnapshotManager(ref: ActorRef): Unit =
    snapshotManager.set(ref)

  /** Gets the registered migration state store, if any. */
  def get: Option[MigrationStateStore] =
    Option(store.get())

  /** Gets the registered SnapshotManager ActorRef, if any. */
  def getSnapshotManager: Option[ActorRef] =
    Option(snapshotManager.get())

  /** Clears all state. Called on simulation shutdown. */
  def clear(): Unit = {
    store.set(null)
    snapshotManager.set(null)
    isMigrationActive.set(false)
  }

  /** Returns true if a migration state store is registered (legacy check). */
  def isRegistered: Boolean =
    store.get() != null
}
