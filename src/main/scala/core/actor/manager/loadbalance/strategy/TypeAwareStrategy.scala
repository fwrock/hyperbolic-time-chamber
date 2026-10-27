package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.actor.manager.loadbalance.spatial.KdTreeLoadBalancer
import core.entity.control.loadbalance._
import core.enumeration.ShardTypeEnum

import org.apache.pekko.actor.Address

import scala.collection.mutable

/** Type-aware load balancing strategy.
  *
  * '''Philosophy''': actors of the same type stay together. Each actor type gets its own family of
  * shards (`car-shard-0`, `car-shard-1`, …, `node-shard-0`, …). No geographic partitioning is used
  * — entities are grouped purely by their actor class.
  *
  * '''Shard assignment (creation time)''':
  *   - Class metadata determines the type; legacy entity IDs are a fallback.
  *   - A counter per type tracks the current open bucket.
  *   - When the current bucket reaches `maxEntitiesPerShard` (from [[StrategyConfig]]), the next
  *     bucket is opened: `{type}-shard-{N+1}`.
  *   - This naturally handles large populations (e.g. 20M persons → ~400 buckets of 50K each).
  *
  * Runtime planning uses live entity counts as a load proxy and pins infrastructure. It
  * evaluates beneficial projected-load moves when max/min load exceeds 1.5 or a prediction
  * reaches its configured threshold. Neither count nor prediction measures CPU utilization.
  *
  * '''Shard ID format''': `{entityType}-shard-{bucket}`
  *
  * Examples:
  *   - `"car-shard-0"`, `"car-shard-1"` — vehicle shards
  *   - `"node-shard-0"` — node shard (likely a single bucket for most cities)
  *   - `"person-shard-0"` … `"person-shard-399"` — 20M persons at 50K/bucket
  */
class TypeAwareStrategy extends BalancingStrategy {

  override val name: String = "type-aware"

  private var config: StrategyConfig = _
  private var kdTree: KdTreeLoadBalancer = _
  private var predictor: core.actor.manager.loadbalance.prediction.PredictiveBalancer = _
  private var maxShardWeight: Double = 50000.0

  /** Current open bucket index per entity type. */
  private val typeBucket: mutable.Map[String, Int] = mutable.Map.empty

  /** Current entity count per shard ("`{type}-shard-{N}`"). */
  private val shardCount: mutable.Map[String, Int] = mutable.Map.empty

  /** All known shard IDs (for getAllShardIds). */
  private val knownShards: mutable.Set[String] = mutable.Set.empty
  private val entityAssignments = mutable.Map.empty[String, (String, String)]
  private val shardTypes = mutable.Map.empty[String, ShardTypeEnum]

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override def initialize(worldBounds: SpatialBounds, cfg: StrategyConfig): Unit = {
    config = cfg
    require(cfg.maxEntitiesPerShard > 0, "type-aware shard capacity must be positive")
    typeBucket.clear()
    shardCount.clear()
    knownShards.clear()
    entityAssignments.clear()
    shardTypes.clear()
    kdTree = new KdTreeLoadBalancer()
    predictor = new core.actor.manager.loadbalance.prediction.PredictiveBalancer(
      loadThreshold = cfg.loadThreshold,
      predictionWindowSeconds = cfg.predictionWindowSeconds,
      maxSamples = cfg.flowVectorSamples
    )
    maxShardWeight = cfg.maxEntitiesPerShard.toDouble
  }

  // ── Core interface ─────────────────────────────────────────────────────────

  /** Assigns a shard of the form `{type}-shard-{bucket}`.
    *
    * If the current bucket for `type` is full (≥ `maxEntitiesPerShard`), a new bucket is opened.
    * Assignment is sequential and thread-safe within the actor model (single-threaded message
    * processing by the LoadBalanceManager).
    */
  override def assignShard(entity: SpatialEntity): String = {
    val entityType = extractType(entity)
    entityAssignments.get(entity.spatialEntityId) match {
      case Some((kind, shard)) =>
        require(kind == entityType, "registered entity actor type changed")
        return shard
      case None => ()
    }
    val bucket = typeBucket.getOrElse(entityType, 0)
    val shardId = s"$entityType-shard-$bucket"

    val count = shardCount.getOrElse(shardId, 0)
    val assigned = if (count >= config.maxEntitiesPerShard) {
      // Current bucket full — open the next one
      val nextBucket = bucket + 1
      typeBucket.put(entityType, nextBucket)
      val nextShardId = s"$entityType-shard-$nextBucket"
      shardCount.put(nextShardId, 1)
      knownShards.add(nextShardId)
      nextShardId
    } else {
      shardCount.put(shardId, count + 1)
      knownShards.add(shardId)
      shardId
    }
    entityAssignments(entity.spatialEntityId) = entityType -> assigned
    shardTypes(assigned) = SpatialEntityClassification.classify(entity)
    assigned
  }

  /** Returns a type-aware shard for the given position. Since this strategy has no geographic
    * partitioning, falls back to an entity-less type bucket. Callers that know the entity type
    * should use [[assignShard]] directly.
    */
  override def getShardForPosition(x: Double, y: Double): String = {
    // Without an entity ID we cannot determine the type; return a generic bucket.
    val hash = ((x * 31 + y) % 1000).toInt.abs
    s"unknown-shard-$hash"
  }

  override def registerNode(address: Address): Unit = kdTree.registerNode(address)
  override def removeNode(address: Address): Unit = kdTree.removeNode(address)

  override def updateMetrics(metrics: ShardMetrics): Unit = {
    kdTree.updateMetrics(metrics)
    if (config.enablePrediction) predictor.recordMetrics(metrics)
  }

  /** Evaluates migration need from the supplied scalar load snapshot.
    *
    *   1. Predictive: shards trending toward overload are moved pre-emptively. 2. Reactive: projected-load
    *      planning runs when max/min pod load ratio > 1.5.
    *
    * Target pod selection is purely load-based (lowest available load). There is no geographic
    * component in this strategy.
    */
  override def evaluate(): List[MigrationPlan] = {
    val predictions = if (config.enablePrediction)
      predictor.predictOverloadedShards(maxShardWeight).map(p => p.shardId -> p).toMap
    else Map.empty[String, core.actor.manager.loadbalance.prediction.PredictionResult]
    if (kdTree.getImbalanceRatio <= 1.5 && predictions.isEmpty) return Nil
    kdTree.rebalance(isMigratable).map { case (shard, source, target) =>
      val prediction = predictions.get(shard)
      val reason = prediction.fold(MigrationReason.Rebalance)(p =>
        if (p.isAlreadyOverloaded) MigrationReason.ReactiveOverload else MigrationReason.PredictiveOverload)
      MigrationPlan(shard, source.get, target, reason,
        predictedLoadAtTarget = prediction.fold(0.0)(_.predictedLoad),
        priority = prediction.fold(1)(p => if (p.isAlreadyOverloaded) 10 else 5))
    }.sortBy(p => (-p.priority, p.shardId))
  }

  override def getAssignments: Map[String, Address] = kdTree.getAllAssignments

  override def getAllShardIds: Set[String] = knownShards.toSet

  override def recordShardLocation(shardId: String, address: Address): Unit =
    kdTree.recordNodeAssignment(shardId, address)
  override def replaceLoadSnapshot(metrics: Seq[ShardMetrics], assignments: Map[String, Address], nodes: Set[Address]): Unit = {
    kdTree.replaceSnapshot(metrics, assignments, nodes)
    if (config.enablePrediction) metrics.foreach(predictor.recordMetrics)
  }

  override def getShardEntityCounts: Map[String, Int] = shardCount.toMap

  /** Type buckets retain their shared infrastructure classification. */
  override def getShardType(shardId: String): ShardTypeEnum = shardTypes.getOrElse(shardId, ShardTypeEnum.Dynamic)

  override def isMigratable(shardId: String): Boolean = getShardType(shardId) == ShardTypeEnum.Dynamic

  override def getStats: Map[String, Any] = {
    val perType = shardCount.keys
      .flatMap(
        s => extractTypeFromShardId(s).map(_ -> s)
      )
      .groupBy(_._1)
      .map {
        case (t, pairs) =>
          t -> Map(
            "shards" -> pairs.size,
            "entities" -> pairs
              .map(
                p => shardCount.getOrElse(p._2, 0)
              )
              .sum
          )
      }
    Map(
      "strategy" -> name,
      "totalShards" -> knownShards.size,
      "clusterNodes" -> kdTree.nodeCount,
      "imbalanceRatio" -> kdTree.getImbalanceRatio,
      "byType" -> perType,
      "nodeLoads" -> kdTree.getNodeLoads.map {
        case (a, l) => a.toString -> l
      }
    )
  }

  override def shutdown(): Unit = predictor.clearAll()

  // ── Internal helpers ───────────────────────────────────────────────────────

  /** Derives a stable, per-type shard key from the entity's `classType` (e.g. `hybrid.actor.Node`
    * → `node`). Falls back to parsing the legacy `htcaid:{type};{id}` convention when `classType` is
    * absent.
    */
  private def extractType(entity: SpatialEntity): String = {
    val ct = entity.entityClassType
    if (ct != null && ct.nonEmpty) {
      val idx = ct.lastIndexOf('.')
      (if (idx >= 0) ct.substring(idx + 1) else ct).toLowerCase
    } else {
      extractTypeFromId(entity.spatialEntityId)
    }
  }

  /** Legacy fallback: extracts the entity type from an ID of the form `htcaid:{type};{id}`. */
  private def extractTypeFromId(entityId: String): String = {
    val lower = entityId.toLowerCase
    val colonIdx = lower.indexOf(':')
    val semicolonIdx = lower.indexOf(';')
    if (colonIdx >= 0 && semicolonIdx > colonIdx) {
      lower.substring(colonIdx + 1, semicolonIdx)
    } else {
      // Fallback: hash-based unknown type
      "unknown"
    }
  }

  /** Extracts the type prefix from a shard ID `{type}-shard-{N}`. */
  private def extractTypeFromShardId(shardId: String): Option[String] = {
    val suffix = "-shard-"
    val idx = shardId.lastIndexOf(suffix)
    if (idx > 0) Some(shardId.substring(0, idx)) else None
  }

  /** Returns the pod with the lowest current load, excluding the given source. */
  private def selectLeastLoaded(sourceNode: Address): Option[Address] =
    kdTree.getNodeLoads
      .filter(_._1 != sourceNode)
      .toVector.sortBy { case (node, load) => (load, node.toString) }.headOption
      .map(_._1)
}
