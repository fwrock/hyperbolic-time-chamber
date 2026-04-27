package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import core.entity.control.loadbalance.{ SpatialBounds, SpatialEntity }

import scala.collection.mutable

/** Linear Quadtree for spatial partitioning of simulation entities.
  *
  * Uses Morton codes (Z-order curve) for efficient spatial hashing and supports the 2:1 balance
  * refinement from "Bottom-up Construction and 2:1 Balance Refinement of Linear Octrees in
  * Parallel" (Sundar et al., 2008), adapted to 2D (quadtree).
  *
  * The tree assigns stable shard IDs to fixed infrastructure (Links, Nodes) while allowing dynamic
  * entities (Vehicles) to be assigned to shards based on their current position.
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

  /** Returns the shard ID for a given position. */
  def getShardId(x: Double, y: Double): String = {
    val leaf = findLeaf(root, x, y)
    leaf.map(_.shardId).getOrElse(root.shardId)
  }

  /** Registers an entity in the quadtree. Returns the assigned shard ID. */
  def registerEntity(entity: SpatialEntity): String = {
    val (x, y) = entity.position
    val shardId = getShardId(x, y)
    entityShardMap.put(entity.spatialEntityId, shardId)
    shardEntities.getOrElseUpdate(shardId, mutable.Set.empty).add(entity.spatialEntityId)
    shardId
  }

  /** Removes an entity from the quadtree. */
  def removeEntity(entityId: String): Unit =
    entityShardMap.remove(entityId).foreach {
      shardId =>
        shardEntities.get(shardId).foreach(_.remove(entityId))
    }

  /** Updates an entity's position. Returns new shard ID if changed, None if same. */
  def updateEntityPosition(entityId: String, newX: Double, newY: Double): Option[String] = {
    val newShardId = getShardId(newX, newY)
    entityShardMap.get(entityId) match {
      case Some(currentShardId) if currentShardId == newShardId =>
        None
      case Some(currentShardId) =>
        shardEntities.get(currentShardId).foreach(_.remove(entityId))
        entityShardMap.put(entityId, newShardId)
        shardEntities.getOrElseUpdate(newShardId, mutable.Set.empty).add(entityId)
        Some(newShardId)
      case None =>
        entityShardMap.put(entityId, newShardId)
        shardEntities.getOrElseUpdate(newShardId, mutable.Set.empty).add(entityId)
        Some(newShardId)
    }
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
    * Two leaves are adjacent if their bounds share an edge or a corner (i.e., the given shard's
    * bounds, expanded by a small epsilon, intersects the neighbor's bounds). The shard itself is
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
    * Splits leaves with too many entities and merges sparse siblings.
    */
  def refine(): Unit =
    root = refineNode(root)

  /** Applies 2:1 balance constraint to the quadtree.
    *
    * Ensures neighboring leaves differ by at most one level of depth. Inspired by the bottom-up
    * approach in Sundar et al. (2008): avoids extreme density gradients that would cause poor shard
    * boundary transitions.
    *
    * This is used for PARTITIONING quality, not construction.
    */
  def applyTwoToOneBalance(): Unit =
    root = balanceTwoToOne(root)

  /** Gets the total number of registered entities. */
  def entityCount: Int = entityShardMap.size

  /** Gets the total number of shards (leaf nodes). */
  def shardCount: Int = collectLeaves(root).size

  // ── Internal Quadtree Operations ──────────────────────────────────────────

  private def findLeaf(node: QuadtreeNode, x: Double, y: Double): Option[QuadtreeLeaf] =
    node match {
      case leaf: QuadtreeLeaf =>
        if (leaf.bounds.contains(x, y)) Some(leaf)
        else None
      case branch: QuadtreeBranch =>
        val children = List(branch.nw, branch.ne, branch.sw, branch.se)
        children.flatMap(findLeaf(_, x, y)).headOption
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
      // Split this leaf into 4 children
      val (nwB, neB, swB, seB) = leaf.bounds.quadrants
      val nw = QuadtreeLeaf(mortonCode(leaf.mortonCode, 0, leaf.depth), leaf.depth + 1, nwB)
      val ne = QuadtreeLeaf(mortonCode(leaf.mortonCode, 1, leaf.depth), leaf.depth + 1, neB)
      val sw = QuadtreeLeaf(mortonCode(leaf.mortonCode, 2, leaf.depth), leaf.depth + 1, swB)
      val se = QuadtreeLeaf(mortonCode(leaf.mortonCode, 3, leaf.depth), leaf.depth + 1, seB)

      // Redistribute entities
      leaf.entities.foreach {
        entityId =>
          entityShardMap.get(entityId) match {
            case Some(_) =>
              // Entity position needs to be re-calculated
              // For now, we hash into appropriate child based on shard mapping
              val childLeaf =
                List(nw, ne, sw, se).find(_.bounds.contains(0, 0)).getOrElse(nw)
              childLeaf.entities.add(entityId)
            case None => ()
          }
      }

      QuadtreeBranch(leaf.mortonCode, leaf.depth, leaf.bounds, nw, ne, sw, se)

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
    * Walk the tree bottom-up. For each leaf, check if any neighbor differs by more than 1 depth
    * level. If so, split the coarser neighbor to restore 2:1 balance.
    */
  private def balanceTwoToOne(node: QuadtreeNode): QuadtreeNode = {
    val leaves = collectLeaves(node)
    var needsAnotherPass = false

    for (leaf <- leaves) {
      val neighbors = findNeighboringLeaves(node, leaf)
      for (neighbor <- neighbors)
        if (leaf.depth - neighbor.depth > 1) {
          needsAnotherPass = true
        }
    }

    if (needsAnotherPass) {
      val refined = forceBalanceSplit(node)
      balanceTwoToOne(refined)
    } else {
      node
    }
  }

  /** Force-split any leaf whose neighbor is more than 1 level deeper. */
  private def forceBalanceSplit(node: QuadtreeNode): QuadtreeNode = node match {
    case leaf: QuadtreeLeaf if leaf.depth < maxDepth =>
      val neighbors = findNeighboringLeaves(root, leaf)
      val maxNeighborDepth = if (neighbors.nonEmpty) neighbors.map(_.depth).max else leaf.depth
      if (maxNeighborDepth - leaf.depth > 1) {
        val (nwB, neB, swB, seB) = leaf.bounds.quadrants
        QuadtreeBranch(
          leaf.mortonCode,
          leaf.depth,
          leaf.bounds,
          QuadtreeLeaf(mortonCode(leaf.mortonCode, 0, leaf.depth), leaf.depth + 1, nwB),
          QuadtreeLeaf(mortonCode(leaf.mortonCode, 1, leaf.depth), leaf.depth + 1, neB),
          QuadtreeLeaf(mortonCode(leaf.mortonCode, 2, leaf.depth), leaf.depth + 1, swB),
          QuadtreeLeaf(mortonCode(leaf.mortonCode, 3, leaf.depth), leaf.depth + 1, seB)
        )
      } else leaf

    case branch: QuadtreeBranch =>
      QuadtreeBranch(
        branch.mortonCode,
        branch.depth,
        branch.bounds,
        forceBalanceSplit(branch.nw),
        forceBalanceSplit(branch.ne),
        forceBalanceSplit(branch.sw),
        forceBalanceSplit(branch.se)
      )

    case _ => node
  }

  /** Find leaves that are spatial neighbors of a given leaf. */
  private def findNeighboringLeaves(
    root: QuadtreeNode,
    target: QuadtreeLeaf
  ): List[QuadtreeLeaf] = {
    val epsilon = target.bounds.width * 0.01
    val searchBounds = SpatialBounds(
      target.bounds.minX - epsilon,
      target.bounds.minY - epsilon,
      target.bounds.maxX + epsilon,
      target.bounds.maxY + epsilon
    )
    collectLeavesInBounds(root, searchBounds).filterNot(_.mortonCode == target.mortonCode)
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

  /** Compute a child morton code from parent code, child index (0-3), and parent depth. */
  private def mortonCode(parentCode: Long, childIndex: Int, parentDepth: Int): Long =
    (parentCode << 2) | childIndex.toLong
}

/** Base trait for quadtree nodes. */
sealed trait QuadtreeNode {
  def mortonCode: Long
  def depth: Int
  def bounds: SpatialBounds

  /** Shard ID derived from morton code. Provides stable, deterministic shard naming. */
  def shardId: String = s"qt-shard-$mortonCode"
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
