package org.interscity.htc
package core.actor.manager.loadbalance.migration

import java.util.concurrent.ConcurrentHashMap

/** In-memory migration state store for single-node setups or testing.
  *
  * Uses a [[ConcurrentHashMap]] to store serialized actor state during shard migration.
  * This is simpler and faster than Redis but has limitations:
  *   - Only accessible from the node hosting the LoadBalanceManager singleton
  *   - State is lost if the LBM node crashes during migration
  *
  * For multi-node production clusters, use [[RedisMigrationStateStore]] instead.
  *
  * Thread safety: ConcurrentHashMap provides thread-safe access from multiple actor contexts.
  */
class InMemoryMigrationStateStore extends MigrationStateStore {

  /** Internal storage: entityId → (stateBytes, className) */
  private val store = new ConcurrentHashMap[String, (Array[Byte], String)]()

  override def saveState(
    entityId: String,
    stateBytes: Array[Byte],
    className: String,
    ttlSeconds: Int = 300
  ): Unit = {
    store.put(entityId, (stateBytes, className))
  }

  override def loadAndRemoveState(entityId: String): Option[(Array[Byte], String)] =
    Option(store.remove(entityId))

  override def hasState(entityId: String): Boolean =
    store.containsKey(entityId)

  override def removeState(entityId: String): Unit =
    store.remove(entityId)

  override def clear(): Unit =
    store.clear()

  override def size: Int =
    store.size()

  override def name: String = "inmemory"
}
