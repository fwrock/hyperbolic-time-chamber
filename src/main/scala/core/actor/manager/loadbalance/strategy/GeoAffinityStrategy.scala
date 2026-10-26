package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.actor.manager.loadbalance.prediction.PredictiveBalancer
import core.actor.manager.loadbalance.spatial.{ KdTreeLoadBalancer, QuadtreePartitioner, SpatialShardBuckets }
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
  * the strategy scores each candidate using a weighted combination of scalar load and geographic
  * affinity:
  * {{{
  *        score(pod) = (1 - α) * (1 - load(pod) / totalCandidateLoad) + α * affinity(pod, dynamicShardId)
  * }}}
  * where `α = geographicAffinityWeight` (default 0.4 — configurable in [[StrategyConfig]]) and
  * `affinity` rewards pods that already host:
  *   - The corresponding static shard for the same quadrant → `1.0`
  *   - Any adjacent static shard (spatially neighboring quadrant) → `0.5`
  *   - No geographic relationship → `0.0`
  *
  * Shard ID convention:
  *   - `"static-qt-shard-D-N"` — pinned infrastructure shard at depth D with Morton code N
  *   - `"dynamic-qt-shard-D-N"` — migratable vehicle shard
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
  private var buckets: SpatialShardBuckets = _

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override def initialize(worldBounds: SpatialBounds, cfg: StrategyConfig): Unit = {
    config = cfg
    shardTypeMap.clear()
    quadtree = new QuadtreePartitioner(
      bounds = worldBounds,
      maxDepth = cfg.maxDepth,
      maxEntitiesPerLeaf = cfg.maxEntitiesPerShard,
      minEntitiesPerLeaf = cfg.minEntitiesPerShard
    )
    quadtree.initializeDepth(cfg.initialSpatialDepth)
    buckets = new SpatialShardBuckets(cfg.maxEntitiesPerShard)
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

  /** Assigns a prefixed shard ID: `"static-qt-shard-D-N"` or `"dynamic-qt-shard-D-N"`.
    *
    * The quadtree provides the base geographic shard ID; this method attaches the namespace prefix
    * so that static and dynamic entities in the same quadrant end up in different shards.
    */
  override def assignShard(entity: SpatialEntity): String = {
    val baseId = quadtree.registerEntity(entity)
    val isStatic = SpatialEntityClassification.classify(entity) == ShardTypeEnum.Static
    val shardId = buckets.assign(entity.spatialEntityId, (if (isStatic) STATIC_PREFIX else DYNAMIC_PREFIX) + baseId)
    shardTypeMap.put(shardId, if (isStatic) ShardTypeEnum.Static else ShardTypeEnum.Dynamic)
    shardId
  }

  override def getShardForPosition(x: Double, y: Double): String =
    buckets.candidate(DYNAMIC_PREFIX + quadtree.getShardId(x, y))

  override def registerNode(address: Address): Unit = kdTree.registerNode(address)
  override def removeNode(address: Address): Unit = kdTree.removeNode(address)

  override def updateMetrics(metrics: ShardMetrics): Unit = {
    kdTree.updateMetrics(metrics)
    if (config.enablePrediction) predictor.recordMetrics(metrics)
  }

  /** Affinity ranks only targets that strictly improve projected scalar-load balance. */
  override def evaluate(): List[MigrationPlan] = {
    val predictions = if (config.enablePrediction)
      predictor.predictOverloadedShards(maxShardWeight).map(p => p.shardId -> p).toMap
    else Map.empty[String, core.actor.manager.loadbalance.prediction.PredictionResult]
    if (kdTree.getImbalanceRatio <= 1.5 && predictions.isEmpty) return Nil
    kdTree.rebalance(isMigratable, affinityScore).map { case (shard, source, target) =>
      val prediction = predictions.get(shard)
      val reason = prediction.fold(MigrationReason.Rebalance)(p =>
        if (p.isAlreadyOverloaded) MigrationReason.ReactiveOverload else MigrationReason.PredictiveOverload)
      MigrationPlan(shard, source.get, target, reason,
        predictedLoadAtTarget = prediction.fold(0.0)(_.predictedLoad),
        priority = prediction.fold(1)(p => if (p.isAlreadyOverloaded) 10 else 5))
    }.sortBy(p => (-p.priority, p.shardId))
  }

  override def getAssignments: Map[String, Address] = kdTree.getAllAssignments

  override def getAllShardIds: Set[String] = buckets.shardIds

  override def getShardEntityCounts: Map[String, Int] = buckets.entityCounts

  override def recordShardLocation(shardId: String, address: Address): Unit =
    kdTree.recordNodeAssignment(shardId, address)
  override def replaceLoadSnapshot(metrics: Seq[ShardMetrics], assignments: Map[String, Address], nodes: Set[Address]): Unit = {
    kdTree.replaceSnapshot(metrics, assignments, nodes)
    if (config.enablePrediction) metrics.foreach(predictor.recordMetrics)
  }

  /** Shard type is encoded in the ID prefix — no need to consult the shardTypeMap. */
  override def getShardType(shardId: String): ShardTypeEnum =
    if (shardId.startsWith(STATIC_PREFIX)) ShardTypeEnum.Static
    else shardTypeMap.getOrElse(shardId, ShardTypeEnum.Dynamic)

  override def isMigratable(shardId: String): Boolean =
    !shardId.startsWith(STATIC_PREFIX)

  override def getStats: Map[String, Any] = Map(
    "strategy" -> name,
    "shardCount" -> buckets.shardIds.size,
    "spatialCells" -> quadtree.shardCount,
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
    *   - `1.0` if the pod already hosts the corresponding static shard (`"static-qt-shard-D-N"`)
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
  private def affinityScore(shardId: String, target: Address, candidateLoads: Map[Address, Double]): Double = {
    val base = stripPrefix(shardId).split("-bucket-").head
    val hosted = kdTree.getAllAssignments.collect { case (id, node) if node == target => id }.toSet
    val staticCells = hosted.filter(_.startsWith(STATIC_PREFIX)).map(id => stripPrefix(id).split("-bucket-").head)
    val affinity = if (staticCells.contains(base)) 1.0
      else if (quadtree.getAdjacentShardIds(base).exists(staticCells.contains)) 0.5
      else 0.0
    val total = candidateLoads.values.sum.max(1e-10)
    (1.0 - config.geographicAffinityWeight) * (1.0 - candidateLoads(target) / total) +
      config.geographicAffinityWeight * affinity
  }

  /** Strips the `"static-"` or `"dynamic-"` prefix to recover the base quadtree shard ID. */
  private def stripPrefix(shardId: String): String =
    if (shardId.startsWith(STATIC_PREFIX)) shardId.drop(STATIC_PREFIX.length)
    else if (shardId.startsWith(DYNAMIC_PREFIX)) shardId.drop(DYNAMIC_PREFIX.length)
    else shardId

}
