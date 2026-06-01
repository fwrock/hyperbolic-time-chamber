package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe global registry mapping entity IDs to spatially-assigned shard IDs.
  *
  * This registry decouples the spatial assignment (performed by [[LoadBalanceManager]]) from the
  * shard routing (performed by Pekko's `extractShardId` in [[core.util.ActorCreatorUtil]]).
  *
  * Flow:
  *   1. During entity creation, [[core.actor.manager.load.CreatorLoadData]] sends batch spatial
  *      data to [[core.actor.manager.loadbalance.LoadBalanceManager]]. 2. LoadBalanceManager
  *      assigns shard IDs via the Quadtree/strategy. 3. Assignments are stored here by
  *      CreatorLoadData when it receives the response. 4. When Pekko routes messages,
  *      `extractShardId` in ActorCreatorUtil checks this registry first; if found, uses the spatial
  *      shard ID instead of hash-based.
  *
  * Also stores entity positions so that entities with indirect spatial data (e.g., links
  * referencing node IDs) can look up node coordinates during creation.
  *
  * Lifecycle: populated during actor creation, cleared on simulation stop via
  * [[LoadBalanceManager.handleStopSimulation]].
  */
object SpatialShardIdRegistry {

  /** Entity ID → spatially-assigned shard ID. */
  private val shardAssignments = new ConcurrentHashMap[String, String]()

  /** Entity ID → position (x/lon, y/lat). Used by creation pipeline to resolve node positions for
    * entities like links and cars that reference node IDs.
    */
  private val entityPositions = new ConcurrentHashMap[String, (Double, Double)]()

  /** Stores a spatial shard assignment for an entity. */
  def putShardId(entityId: String, shardId: String): Unit =
    shardAssignments.put(entityId, shardId)

  /** Retrieves the spatial shard assignment for an entity, if any. */
  def getShardId(entityId: String): Option[String] =
    Option(shardAssignments.get(entityId))

  /** Checks whether a spatial assignment exists for the given entity. */
  def hasShardId(entityId: String): Boolean =
    shardAssignments.containsKey(entityId)

  // ── Position operations ──────────────────────────────────────────────────

  /** Stores an entity's spatial position for later lookup. Primarily used for nodes so that
    * links/cars can resolve positions via node IDs.
    */
  def putPosition(entityId: String, position: (Double, Double)): Unit =
    entityPositions.put(entityId, position)

  /** Retrieves a previously stored entity position, if any. */
  def getPosition(entityId: String): Option[(Double, Double)] =
    Option(entityPositions.get(entityId))

  /** Stores multiple shard assignments at once. */
  def putAllShardIds(assignments: Map[String, String]): Unit =
    assignments.foreach {
      case (entityId, shardId) => shardAssignments.put(entityId, shardId)
    }

  /** Stores multiple entity positions at once. Accepts Array[Double] (index 0 = lon, 1 = lat) from
    * the wire-safe response format.
    */
  def putAllPositions(positions: Map[String, Array[Double]]): Unit =
    positions.foreach {
      case (entityId, arr) => entityPositions.put(entityId, (arr(0), arr(1)))
    }

  /** Returns all entity IDs assigned to a given shard.
    *
    * This performs a linear scan over all assignments — suitable for migration notifications but
    * not for hot-path operations.
    */
  def getEntitiesInShard(shardId: String): Set[String] = {
    import scala.jdk.CollectionConverters._
    shardAssignments
      .entrySet()
      .asScala
      .filter(_.getValue == shardId)
      .map(_.getKey)
      .toSet
  }

  /** Entity ID → fully qualified shard region class name (for routing messages). */
  private val entityClassNames = new ConcurrentHashMap[String, String]()

  /** Stores the shard region class name for an entity. */
  def putEntityClassName(entityId: String, className: String): Unit =
    entityClassNames.put(entityId, className)

  /** Retrieves the shard region class name for an entity, if any. */
  def getEntityClassName(entityId: String): Option[String] =
    Option(entityClassNames.get(entityId))

  /** Stores multiple entity class names at once. */
  def putAllEntityClassNames(names: Map[String, String]): Unit =
    names.foreach {
      case (entityId, className) => entityClassNames.put(entityId, className)
    }

  /** Clears all registry data. Called on simulation stop. */
  def clear(): Unit = {
    shardAssignments.clear()
    entityPositions.clear()
    entityClassNames.clear()
  }

  /** Clears only the entity-position cache.
   *
   * Positions are required only during entity creation (used by
   * [[core.actor.manager.load.CreatorLoadData.extractSpatialPosition]] to resolve indirect
   * references such as a link's `from` node). Once **all** sources — EAGER and PROGRESSIVE —
   * have finished loading, no further actors will be created, so this map can be safely released
   * to give the GC a chance to reclaim the memory mid-simulation.
   *
   * `shardAssignments` and `entityClassNames` MUST remain populated, since they are consulted by
   * the routing layer for every message dispatch during the simulation.
   */
  def clearPositions(): Unit =
    entityPositions.clear()

  /** Returns the number of entity-to-shard assignments stored. */
  def size: Int = shardAssignments.size()

  /** Returns the number of entity positions stored. */
  def positionCount: Int = entityPositions.size()

  /** Whether any spatial assignments have been registered. */
  def isActive: Boolean = !shardAssignments.isEmpty
}
