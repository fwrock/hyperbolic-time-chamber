package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import core.entity.control.loadbalance.{ SpatialBounds, SpatialEntity, SpatialEntityData }

import scala.collection.mutable

/** Region quadtree with Morton-labelled shards for spatial partitioning of simulation entities.
  *
  * Uses Morton codes (Z-order curve) for efficient spatial hashing and supports the 2:1 balance
  * refinement from "Bottom-up Construction and 2:1 Balance Refinement of Linear Octrees in
  * Parallel" (Sundar et al., 2008), adapted to 2D (quadtree).
  *
  * Refinement changes entity shard IDs. Callers must publish the resulting assignments before
  * using them for routing; this component does not coordinate live shard membership changes.
  *
  * @param bounds
  *   The world bounds encompassing all entities
  * @param maxDepth
  *   Maximum depth of the quadtree (determines granularity)
  * @param maxEntitiesPerLeaf
  *   Maximum entities before a leaf splits
  * @param minEntitiesPerLeaf
  *   Minimum entities before siblings merge
  */
class QuadtreePartitioner(
  val bounds: SpatialBounds,
  val maxDepth: Int = 8,
  val maxEntitiesPerLeaf: Int = 10000,
  val minEntitiesPerLeaf: Int = 100
) {
  require(maxDepth >= 0 && maxDepth <= 31, "Quadtree depth must be between 0 and 31")
  require(maxEntitiesPerLeaf > 0, "Quadtree leaf capacity must be positive")
  require(minEntitiesPerLeaf >= 0 && minEntitiesPerLeaf <= maxEntitiesPerLeaf,
    "Quadtree merge threshold must be between zero and leaf capacity")
  require(Seq(bounds.minX, bounds.minY, bounds.maxX, bounds.maxY,
    bounds.width, bounds.height, bounds.centerX, bounds.centerY).forall(_.isFinite) &&
    bounds.width > 0 && bounds.height > 0, "Quadtree bounds must be finite and have positive extent")

  /** Root node of the quadtree */
  private var root: QuadtreeNode = QuadtreeLeaf(
    mortonCode = 0L,
    depth = 0,
    bounds = bounds,
    entities = mutable.Set.empty
  )

  /** Entity-to-shard mapping for fast lookup */
  private val entityShardMap: mutable.Map[String, String] = mutable.Map.empty

  /** Shard-to-entities mapping */
  private val shardEntities: mutable.Map[String, mutable.Set[String]] = mutable.Map.empty

  private val entityPositions: mutable.Map[String, (Double, Double)] = mutable.Map.empty

  /** Returns the shard ID for a given position. */
  def getShardId(x: Double, y: Double): String = {
    validatePosition(x, y)
    val leaf = findLeaf(root, x, y)
    leaf.shardId
  }

  /** Registers an entity in the quadtree. Returns the assigned shard ID. */
  def initializeDepth(depth: Int): Unit = {
    require(entityCount == 0 && depth >= 0 && depth <= maxDepth && depth <= 8,
      "initial spatial depth requires an empty tree and must be within [0, min(maxDepth, 8)]")
    def expand(node: QuadtreeNode): QuadtreeNode = node match {
      case leaf: QuadtreeLeaf if leaf.depth < depth => expand(splitLeaf(leaf))
      case branch: QuadtreeBranch => branch.copy(nw = expand(branch.nw), ne = expand(branch.ne),
        sw = expand(branch.sw), se = expand(branch.se))
      case leaf => leaf
    }
    root = expand(root)
  }

  def registerEntity(entity: SpatialEntity): String = {
    val (x, y) = entity.position
    validatePosition(x, y)
    removeEntity(entity.spatialEntityId)
    val leaf = findLeaf(root, x, y)
    val shardId = leaf.shardId
    leaf.entities.add(entity.spatialEntityId)
    entityPositions.put(entity.spatialEntityId, (x, y))
    entityShardMap.put(entity.spatialEntityId, shardId)
    shardEntities.getOrElseUpdate(shardId, mutable.Set.empty).add(entity.spatialEntityId)
    shardId
  }

  /** Removes an entity from the quadtree. */
  def removeEntity(entityId: String): Unit = {
    entityPositions.remove(entityId).foreach { case (x, y) =>
      findLeaf(root, x, y).entities.remove(entityId)
    }
    entityShardMap.remove(entityId).foreach {
      shardId =>
        shardEntities.get(shardId).foreach { entities =>
          entities.remove(entityId)
          if (entities.isEmpty) shardEntities.remove(shardId)
        }
    }
  }

  /** Updates an entity's position. Returns new shard ID if changed, None if same. */
  def updateEntityPosition(entityId: String, newX: Double, newY: Double): Option[String] = {
    val previous = entityShardMap.get(entityId)
    val shardId = registerEntity(SpatialEntityData(entityId, newX, newY))
    Option.when(!previous.contains(shardId))(shardId)
  }

  /** Gets all shard IDs currently in use. */
  def getAllShardIds: Set[String] = collectLeaves(root).map(_.shardId).toSet

  /** Gets entity count per shard. */
  def getShardEntityCounts: Map[String, Int] =
    shardEntities.view.mapValues(_.size).toMap

  /** Gets the shard ID for a specific entity. */
  def getEntityShard(entityId: String): Option[String] =
    entityShardMap.get(entityId)

  /** Gets all entities in a specific shard. */
  def getEntitiesInShard(shardId: String): Set[String] =
    shardEntities.getOrElse(shardId, mutable.Set.empty).toSet

  /** Gets the bounds for a specific shard. */
  def getShardBounds(shardId: String): Option[SpatialBounds] =
    findLeafByShardId(root, shardId).map(_.bounds)

  /** Returns all leaf nodes (shards). */
  def getLeaves: List[QuadtreeLeaf] = collectLeaves(root)

  /** Returns the shard IDs of all leaves that are spatially adjacent to the given shard.
    *
    * Two leaves are adjacent if their bounds share an edge or a corner. The shard itself is
    * excluded from the result.
    *
    * @param shardId
    *   The base shard ID (without any prefix)
    * @return
    *   Set of adjacent shard IDs, empty if the shard is not found
    */
  def getAdjacentShardIds(shardId: String): Set[String] =
    findLeafByShardId(root, shardId)
      .map(
        leaf => findNeighboringLeaves(root, leaf).map(_.shardId).toSet
      )
      .getOrElse(Set.empty)

  /** Adaptively refines the quadtree based on entity density.
    *
    * Splits leaves with too many entities and merges sparse siblings. Existing shard assignments
    * can change, so this must precede routing or participate in a coordinated membership update.
    */
  def refine(): Unit = {
    root = refineNode(root)
    rebuildShardMaps()
  }

  /** Applies 2:1 balance constraint to the quadtree.
    *
    * Ensures neighboring leaves differ by at most one level of depth. Inspired by the bottom-up
    * approach in Sundar et al. (2008): avoids extreme density gradients that would cause poor shard
    * boundary transitions.
    *
    * Like density refinement, this can change existing shard assignments.
    */
  def applyTwoToOneBalance(): Unit = {
    root = balanceTwoToOne(root)
    rebuildShardMaps()
  }

  /** Gets the total number of registered entities. */
  def entityCount: Int = entityShardMap.size

  /** Gets the total number of shards (leaf nodes). */
  def shardCount: Int = collectLeaves(root).size

  // ── Internal Quadtree Operations ──────────────────────────────────────────

  private def validatePosition(x: Double, y: Double): Unit =
    require(x.isFinite && y.isFinite && bounds.contains(x, y),
      s"Position ($x, $y) is outside finite quadtree bounds $bounds")

  private def findLeaf(node: QuadtreeNode, x: Double, y: Double): QuadtreeLeaf =
    node match {
      case leaf: QuadtreeLeaf => leaf
      case branch: QuadtreeBranch =>
        val child =
          if (y >= branch.bounds.centerY) {
            if (x <= branch.bounds.centerX) branch.nw else branch.ne
          } else {
            if (x <= branch.bounds.centerX) branch.sw else branch.se
          }
        findLeaf(child, x, y)
    }

  private def rebuildShardMaps(): Unit = {
    entityShardMap.clear()
    shardEntities.clear()
    collectLeaves(root).foreach { leaf =>
      if (leaf.entities.nonEmpty) {
        shardEntities.put(leaf.shardId, mutable.Set.from(leaf.entities))
        leaf.entities.foreach(entityShardMap.put(_, leaf.shardId))
      }
    }
  }

  private def findLeafByShardId(node: QuadtreeNode, shardId: String): Option[QuadtreeLeaf] =
    node match {
      case leaf: QuadtreeLeaf =>
        if (leaf.shardId == shardId) Some(leaf) else None
      case branch: QuadtreeBranch =>
        val children = List(branch.nw, branch.ne, branch.sw, branch.se)
        children.flatMap(findLeafByShardId(_, shardId)).headOption
    }

  private def collectLeaves(node: QuadtreeNode): List[QuadtreeLeaf] = node match {
    case leaf: QuadtreeLeaf => List(leaf)
    case branch: QuadtreeBranch =>
      collectLeaves(branch.nw) ++ collectLeaves(branch.ne) ++
        collectLeaves(branch.sw) ++ collectLeaves(branch.se)
  }

  private def refineNode(node: QuadtreeNode): QuadtreeNode = node match {
    case leaf: QuadtreeLeaf if leaf.entities.size > maxEntitiesPerLeaf && leaf.depth < maxDepth =>
      refineNode(splitLeaf(leaf))

    case branch: QuadtreeBranch =>
      val newBranch = QuadtreeBranch(
        branch.mortonCode,
        branch.depth,
        branch.bounds,
        refineNode(branch.nw),
        refineNode(branch.ne),
        refineNode(branch.sw),
        refineNode(branch.se)
      )
      // Check if all children are sparse leaves that should merge
      maybeMerge(newBranch)

    case _ => node
  }

  private def splitLeaf(leaf: QuadtreeLeaf): QuadtreeBranch = {
    val (nw, ne, sw, se) = leaf.bounds.quadrants
    val branch = QuadtreeBranch(
      leaf.mortonCode, leaf.depth, leaf.bounds,
      QuadtreeLeaf(mortonCode(leaf.mortonCode, 0), leaf.depth + 1, nw),
      QuadtreeLeaf(mortonCode(leaf.mortonCode, 1), leaf.depth + 1, ne),
      QuadtreeLeaf(mortonCode(leaf.mortonCode, 2), leaf.depth + 1, sw),
      QuadtreeLeaf(mortonCode(leaf.mortonCode, 3), leaf.depth + 1, se)
    )
    leaf.entities.foreach { id =>
      val (x, y) = entityPositions(id)
      findLeaf(branch, x, y).entities.add(id)
    }
    branch
  }

  private def maybeMerge(branch: QuadtreeBranch): QuadtreeNode = {
    val children = List(branch.nw, branch.ne, branch.sw, branch.se)
    val allLeaves = children.forall(_.isInstanceOf[QuadtreeLeaf])
    if (allLeaves) {
      val totalEntities = children.map(_.asInstanceOf[QuadtreeLeaf].entities.size).sum
      if (totalEntities < minEntitiesPerLeaf) {
        val merged = QuadtreeLeaf(branch.mortonCode, branch.depth, branch.bounds)
        children.foreach {
          child =>
            merged.entities.addAll(child.asInstanceOf[QuadtreeLeaf].entities)
        }
        merged
      } else branch
    } else branch
  }

  /** 2:1 Balance refinement.
    *
    * Each pass uses one topology snapshot so newly split neighbors participate in the next pass.
    */
  private def balanceTwoToOne(node: QuadtreeNode): QuadtreeNode = {
    var balanced = node
    var complete = false
    while (!complete) {
      val coarse = collectLeaves(balanced).filter { leaf =>
        findNeighboringLeaves(balanced, leaf).exists(_.depth - leaf.depth > 1)
      }.map(_.shardId).toSet
      if (coarse.isEmpty) complete = true
      else balanced = forceBalanceSplit(balanced, coarse)
    }
    balanced
  }

  /** Force-split any leaf whose neighbor is more than 1 level deeper. */
  private def forceBalanceSplit(node: QuadtreeNode, coarse: Set[String]): QuadtreeNode = node match {
    case leaf: QuadtreeLeaf if coarse.contains(leaf.shardId) && leaf.depth < maxDepth =>
      splitLeaf(leaf)

    case branch: QuadtreeBranch =>
      QuadtreeBranch(
        branch.mortonCode,
        branch.depth,
        branch.bounds,
        forceBalanceSplit(branch.nw, coarse),
        forceBalanceSplit(branch.ne, coarse),
        forceBalanceSplit(branch.sw, coarse),
        forceBalanceSplit(branch.se, coarse)
      )

    case _ => node
  }

  /** Find leaves that are spatial neighbors of a given leaf. */
  private def findNeighboringLeaves(
    root: QuadtreeNode,
    target: QuadtreeLeaf
  ): List[QuadtreeLeaf] = {
    collectLeavesInBounds(root, target.bounds).filterNot(_.shardId == target.shardId)
  }

  /** Collect all leaves whose bounds intersect the query bounds. */
  private def collectLeavesInBounds(
    node: QuadtreeNode,
    queryBounds: SpatialBounds
  ): List[QuadtreeLeaf] = node match {
    case leaf: QuadtreeLeaf =>
      if (leaf.bounds.intersects(queryBounds)) List(leaf)
      else Nil
    case branch: QuadtreeBranch =>
      if (!branch.bounds.intersects(queryBounds)) Nil
      else
        collectLeavesInBounds(branch.nw, queryBounds) ++
          collectLeavesInBounds(branch.ne, queryBounds) ++
          collectLeavesInBounds(branch.sw, queryBounds) ++
          collectLeavesInBounds(branch.se, queryBounds)
  }

  private def mortonCode(parentCode: Long, childIndex: Int): Long =
    (parentCode << 2) | childIndex.toLong
}

/** Base trait for quadtree nodes. */
sealed trait QuadtreeNode {
  def mortonCode: Long
  def depth: Int
  def bounds: SpatialBounds

  // Leading NW quadrants contribute zero bits, so Morton codes alone collide across depths.
  def shardId: String = s"qt-shard-$depth-$mortonCode"
}

/** Leaf node — represents a single shard that contains entities. */
case class QuadtreeLeaf(
  mortonCode: Long,
  depth: Int,
  bounds: SpatialBounds,
  entities: mutable.Set[String] = mutable.Set.empty
) extends QuadtreeNode

/** Branch node — internal node with 4 children (NW, NE, SW, SE). */
case class QuadtreeBranch(
  mortonCode: Long,
  depth: Int,
  bounds: SpatialBounds,
  nw: QuadtreeNode,
  ne: QuadtreeNode,
  sw: QuadtreeNode,
  se: QuadtreeNode
) extends QuadtreeNode
