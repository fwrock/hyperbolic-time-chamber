package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.actor.manager.loadbalance.prediction.PredictiveBalancer
import core.actor.manager.loadbalance.spatial.{ KdTreeLoadBalancer, QuadtreePartitioner }
import core.entity.control.loadbalance._
import core.enumeration.ShardTypeEnum

import org.apache.pekko.actor.Address

import scala.collection.mutable

/** Geographic Affinity balancing strategy.
  *
  * Builds on the Hybrid strategy with two structural improvements:
  *
  *   1. '''Separated shard namespaces''': Static entities (nodes, links, signals) and dynamic
  *      entities (vehicles, persons) that share the same geographic quadrant are assigned to
  *      distinct shard IDs, prefixed with `"static-"` and `"dynamic-"` respectively. This
  *      eliminates `Mixed` shards entirely — static infrastructure is ''never'' migrated.
  *
  * 2. '''Geographic affinity scoring''': When choosing the target pod for a dynamic shard migration
  * the strategy scores each candidate using a weighted combination of compute load and geographic
  * affinity:
  * {{{
  *        score(pod) = (1 - α) * (1 / load(pod)) + α * affinity(pod, dynamicShardId)
  * }}}
  * where `α = geographicAffinityWeight` (default 0.4 — configurable in [[StrategyConfig]]) and
  * `affinity` rewards pods that already host:
  *   - The corresponding static shard for the same quadrant → `1.0`
  *   - Any adjacent static shard (spatially neighboring quadrant) → `0.5`
  *   - No geographic relationship → `0.0`
  *
  * Shard ID convention:
  *   - `"static-qt-shard-N"` — pinned infrastructure shard, never migrated
  *   - `"dynamic-qt-shard-N"` — migratable vehicle shard
  *
  * The underlying quadtree and kd-tree are the same components used by [[HybridStrategy]]. The
  * difference is only in how shard IDs are assigned and how the migration target is chosen.
  */
class GeoAffinityStrategy extends BalancingStrategy {

  override val name: String = "geo-affinity"

  private val STATIC_PREFIX = "static-"
  private val DYNAMIC_PREFIX = "dynamic-"

  private var quadtree: QuadtreePartitioner = _
  private var kdTree: KdTreeLoadBalancer = _
  private var predictor: PredictiveBalancer = _
  private var config: StrategyConfig = _
  private var maxShardWeight: Double = 10000.0

  private val shardTypeMap: mutable.Map[String, ShardTypeEnum] = mutable.Map.empty

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override def initialize(worldBounds: SpatialBounds, cfg: StrategyConfig): Unit = {
    config = cfg
    quadtree = new QuadtreePartitioner(
      bounds = worldBounds,
      maxDepth = cfg.maxDepth,
      maxEntitiesPerLeaf = cfg.maxEntitiesPerShard,
      minEntitiesPerLeaf = cfg.minEntitiesPerShard
    )
    kdTree = new KdTreeLoadBalancer()
    predictor = new PredictiveBalancer(
      loadThreshold = cfg.loadThreshold,
      predictionWindowSeconds = cfg.predictionWindowSeconds,
      maxSamples = cfg.flowVectorSamples
    )
    if (cfg.enableTwoToOneBalance) quadtree.applyTwoToOneBalance()
    maxShardWeight = cfg.maxEntitiesPerShard.toDouble
  }

  // ── Core interface ─────────────────────────────────────────────────────────

  /** Assigns a prefixed shard ID: `"static-qt-shard-N"` or `"dynamic-qt-shard-N"`.
    *
    * The quadtree provides the base geographic shard ID; this method attaches the namespace prefix
    * so that static and dynamic entities in the same quadrant end up in different shards.
    */
  override def assignShard(entity: SpatialEntity): String = {
    val baseId = quadtree.registerEntity(entity)
    val isStatic = isStaticEntity(entity)
    val shardId = (if (isStatic) STATIC_PREFIX else DYNAMIC_PREFIX) + baseId
    shardTypeMap.put(shardId, if (isStatic) ShardTypeEnum.Static else ShardTypeEnum.Dynamic)
    shardId
  }

  override def getShardForPosition(x: Double, y: Double): String =
    DYNAMIC_PREFIX + quadtree.getShardId(x, y)

  override def registerNode(address: Address): Unit = kdTree.registerNode(address)
  override def removeNode(address: Address): Unit = kdTree.removeNode(address)

  override def updateMetrics(metrics: ShardMetrics): Unit = {
    kdTree.updateMetrics(metrics)
    if (config.enablePrediction) predictor.recordMetrics(metrics)
  }

  /** Evaluates migration need and selects targets using the affinity-weighted score.
    *
    * Migration candidates come from two sources:
    *   1. Predictive analysis (imminent overload of a dynamic shard) 2. Periodic kd-tree rebalance
    *      (CPU imbalance ratio > 1.5 across pods)
    *
    * In both cases, the final target pod is chosen via [[selectTargetWithAffinity]] instead of the
    * plain min-load heuristic used by [[HybridStrategy]].
    *
    * Static shards are filtered out of the result — they are never migrated.
    */
  override def evaluate(): List[MigrationPlan] = {
    val migrations = mutable.ListBuffer[MigrationPlan]()

    // 1. Predictive: flag shards predicted to overflow
    if (config.enablePrediction) {
      predictor.predictOverloadedShards(maxShardWeight).foreach {
        result =>
          val currentAssignment = kdTree.getAssignment(result.shardId)
          currentAssignment.foreach {
            source =>
              val reason =
                if (result.isAlreadyOverloaded) MigrationReason.ReactiveOverload
                else MigrationReason.PredictiveOverload
              selectTargetWithAffinity(result.shardId, source).foreach {
                target =>
                  migrations += MigrationPlan(
                    shardId = result.shardId,
                    sourceNode = source,
                    targetNode = target,
                    reason = reason,
                    predictedLoadAtTarget = result.predictedLoad,
                    priority = if (result.isAlreadyOverloaded) 10 else 5
                  )
              }
          }
      }
    }

    // 2. Periodic kd-tree rebalance when imbalance exceeds 50%
    if (kdTree.getImbalanceRatio > 1.5) {
      kdTree.rebalance().foreach {
        case (shardId, oldNode, newNode) if !migrations.exists(_.shardId == shardId) =>
          val source = oldNode.getOrElse(newNode)
          val betterTarget = selectTargetWithAffinity(shardId, source).getOrElse(newNode)
          migrations += MigrationPlan(
            shardId = shardId,
            sourceNode = source,
            targetNode = betterTarget,
            reason = MigrationReason.Rebalance,
            priority = 1
          )
        case _ => ()
      }
    }

    // 3. Adaptive quadtree refinement
    quadtree.refine()
    if (config.enableTwoToOneBalance) quadtree.applyTwoToOneBalance()

    // Static shards are pinned — filter them out before returning plans
    migrations
      .filter(
        p => isMigratable(p.shardId)
      )
      .toList
      .sortBy(-_.priority)
  }

  override def getAssignments: Map[String, Address] = kdTree.getAllAssignments

  override def getAllShardIds: Set[String] = {
    val baseIds = quadtree.getAllShardIds
    baseIds.flatMap(
      id => Set(STATIC_PREFIX + id, DYNAMIC_PREFIX + id)
    )
  }

  /** Returns entity counts per prefixed shard ID.
    *
    * The quadtree tracks counts by base shard ID (without prefix). We re-key them here using the
    * prefix that was assigned during [[assignShard]] — consulting `shardTypeMap` to determine which
    * prefix each base shard received. Base shards that appear in both namespaces (one static entity
    * and one dynamic entity at the same quadrant) will produce two entries summed from the quadtree
    * count.
    *
    * In practice every base shard is populated with entities of a single type, so the map will have
    * either a `"static-*"` or a `"dynamic-*"` entry per quadrant.
    */
  override def getShardEntityCounts: Map[String, Int] = {
    val baseCounts = quadtree.getShardEntityCounts
    baseCounts.flatMap {
      case (baseId, count) =>
        val staticKey = STATIC_PREFIX + baseId
        val dynamicKey = DYNAMIC_PREFIX + baseId
        val hasStatic = shardTypeMap.get(staticKey).isDefined
        val hasDynamic = shardTypeMap.get(dynamicKey).isDefined
        // Emit only the prefixed keys that were actually registered via assignShard
        Seq(
          if (hasStatic) Some(staticKey -> count) else None,
          if (hasDynamic) Some(dynamicKey -> count) else None
        ).flatten
    }
  }

  override def recordShardLocation(shardId: String, address: Address): Unit =
    kdTree.recordNodeAssignment(shardId, address)

  /** Shard type is encoded in the ID prefix — no need to consult the shardTypeMap. */
  override def getShardType(shardId: String): ShardTypeEnum =
    if (shardId.startsWith(STATIC_PREFIX)) ShardTypeEnum.Static
    else shardTypeMap.getOrElse(shardId, ShardTypeEnum.Dynamic)

  override def isMigratable(shardId: String): Boolean =
    !shardId.startsWith(STATIC_PREFIX)

  override def getStats: Map[String, Any] = Map(
    "strategy" -> name,
    "shardCount" -> quadtree.shardCount,
    "entityCount" -> quadtree.entityCount,
    "staticShards" -> shardTypeMap.count(_._2 == ShardTypeEnum.Static),
    "dynamicShards" -> shardTypeMap.count(_._2 == ShardTypeEnum.Dynamic),
    "clusterNodes" -> kdTree.nodeCount,
    "imbalanceRatio" -> kdTree.getImbalanceRatio,
    "affinityWeight" -> config.geographicAffinityWeight,
    "nodeLoads" -> kdTree.getNodeLoads.map {
      case (a, l) => a.toString -> l
    }
  )

  override def shutdown(): Unit = predictor.clearAll()

  // ── Internal helpers ───────────────────────────────────────────────────────

  /** Selects the best target pod via a weighted load + geographic affinity score.
    *
    * Score formula:
    * {{{
    *   score(pod) = (1 - α) * normalizedInvLoad(pod) + α * affinity(pod, shardId)
    * }}}
    *
    * `normalizedInvLoad` = `1 - load(pod) / totalLoad` so lower-loaded pods score higher.
    *
    * `affinity`:
    *   - `1.0` if the pod already hosts the corresponding static shard (`"static-qt-shard-N"`)
    *   - `0.5` if the pod hosts any spatially adjacent static shard
    *   - `0.0` otherwise
    *
    * @param shardId
    *   A dynamic shard ID (prefixed or unprefixed — both accepted)
    * @param sourceNode
    *   The current pod hosting the shard; excluded from candidates
    * @return
    *   The highest-scoring pod, or `None` if there are no alternatives
    */
  private def selectTargetWithAffinity(shardId: String, sourceNode: Address): Option[Address] = {
    val alpha = config.geographicAffinityWeight
    val nodeLoads = kdTree.getNodeLoads
    val allAssignments = kdTree.getAllAssignments

    val baseId = stripPrefix(shardId)
    val correspondingStatic = STATIC_PREFIX + baseId
    val adjacentStatics = quadtree.getAdjacentShardIds(baseId).map(STATIC_PREFIX + _)

    val candidates = nodeLoads.filter(_._1 != sourceNode)
    if (candidates.isEmpty) return None

    val totalLoad = candidates.values.sum.max(1e-10)

    val scored = candidates.map {
      case (pod, load) =>
        val normalizedInvLoad = 1.0 - (load / totalLoad)
        val hostedShards = allAssignments.filter(_._2 == pod).keySet
        val affinity =
          if (hostedShards.contains(correspondingStatic)) 1.0
          else if (adjacentStatics.exists(hostedShards.contains)) 0.5
          else 0.0
        val score = (1.0 - alpha) * normalizedInvLoad + alpha * affinity
        pod -> score
    }

    scored.maxByOption(_._2).map(_._1)
  }

  /** Strips the `"static-"` or `"dynamic-"` prefix to recover the base quadtree shard ID. */
  private def stripPrefix(shardId: String): String =
    if (shardId.startsWith(STATIC_PREFIX)) shardId.drop(STATIC_PREFIX.length)
    else if (shardId.startsWith(DYNAMIC_PREFIX)) shardId.drop(DYNAMIC_PREFIX.length)
    else shardId

  /** Returns `true` if the entity represents static infrastructure (node, link, signal). */
  private def isStaticEntity(entity: SpatialEntity): Boolean = {
    val id = entity.spatialEntityId.toLowerCase
    id.contains(":node;") || id.contains(":link;") ||
    id.contains(":traffic_signal;") || id.contains(":signal;")
  }
}
