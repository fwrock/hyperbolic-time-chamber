package org.interscity.htc
package core.actor.manager.loadbalance.migration

import java.util.concurrent.atomic.AtomicReference

/** Global registry for the active [[MigrationStateStore]] instance.
  *
  * Provides static access to the migration state store so that actors can save/restore
  * state during shard hand-off without requiring constructor injection. This follows the
  * same pattern as [[core.actor.manager.loadbalance.allocation.ShardAllocatorRegistry]].
  *
  * The store is set by [[core.actor.manager.loadbalance.LoadBalanceManager.onStart()]]
  * and cleared on simulation shutdown.
  *
  * When no store is registered, actors skip migration state save/restore (backward-compatible).
  *
  * Thread-safe via [[AtomicReference]].
  */
object MigrationStateStoreRegistry {

  private val store: AtomicReference[MigrationStateStore] =
    new AtomicReference[MigrationStateStore](null)

  /** Registers the migration state store to be used during shard hand-offs. */
  def register(stateStore: MigrationStateStore): Unit =
    store.set(stateStore)

  /** Gets the registered migration state store, if any. */
  def get: Option[MigrationStateStore] =
    Option(store.get())

  /** Clears the registered store. Called on simulation shutdown. */
  def clear(): Unit =
    store.set(null)

  /** Returns true if a migration state store is registered. */
  def isRegistered: Boolean =
    store.get() != null
}
