package org.interscity.htc
package core.actor.manager.loadbalance.migration

/** Abstraction for storing actor state during shard migration.
  *
  * The migration state store holds serialized actor state between the time an actor is stopped on
  * the source node and re-created on the target node. State entries are short-lived: written before
  * hand-off, read on re-creation, deleted after restore.
  *
  * Implementations:
  *   - [[RedisMigrationStateStore]]: Uses Redis (shared, survives crashes, recommended for
  *     multi-node)
  *   - [[InMemoryMigrationStateStore]]: Uses ConcurrentHashMap (fast, singleton-hosted, for
  *     single-node/testing)
  *
  * Thread safety: All implementations must be thread-safe since they are accessed from multiple
  * actor contexts (the source actor saves, the target actor restores).
  */
trait MigrationStateStore {

  /** Saves serialized actor state for later retrieval after migration.
    *
    * @param entityId
    *   Unique entity identifier (e.g., "htcaid:car;trip_42")
    * @param stateBytes
    *   State serialized as JSON bytes via Jackson
    * @param className
    *   Fully qualified class name of the state type (for deserialization on target)
    * @param ttlSeconds
    *   Time-to-live for the entry; auto-deleted if not consumed (guards against leaks)
    */
  def saveState(
    entityId: String,
    stateBytes: Array[Byte],
    className: String,
    ttlSeconds: Int = 300
  ): Unit

  /** Retrieves and removes the migration state for an entity.
    *
    * This is "get-and-delete" semantics: the state is consumed on first read and cleaned up.
    * Returns None if no migration state exists (normal case for non-migrating actors).
    *
    * @param entityId
    *   Unique entity identifier
    * @return
    *   Some((stateBytes, className)) if migration state exists, None otherwise
    */
  def loadAndRemoveState(entityId: String): Option[(Array[Byte], String)]

  /** Checks whether migration state exists for an entity without consuming it.
    *
    * @param entityId
    *   Unique entity identifier
    * @return
    *   true if migration state is stored for this entity
    */
  def hasState(entityId: String): Boolean

  /** Removes migration state for an entity if it exists. Idempotent.
    *
    * @param entityId
    *   Unique entity identifier
    */
  def removeState(entityId: String): Unit

  /** Removes all stored migration states. Called on simulation shutdown. */
  def clear(): Unit

  /** Returns the number of currently stored migration states. */
  def size: Int

  /** Display name for logging. */
  def name: String
}
