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
  * shards (`car-shard-0`, `car-shard-1`, …, `node-shard-0`, …). No geographic partitioning is
  * used — entities are grouped purely by their actor class.
  *
  * '''Shard assignment (creation time)''':
  *   - The type is extracted from the entity ID convention `htcaid:{type};{id}`.
  *   - A counter per type tracks the current open bucket.
  *   - When the current bucket reaches `maxEntitiesPerShard` (from [[StrategyConfig]]), the next
  *     bucket is opened: `{type}-shard-{N+1}`.
  *   - This naturally handles large populations (e.g. 20M persons → ~400 buckets of 50K each).
  *
  * '''Runtime load balancing''':
  *   - The kd-tree component monitors CPU load metrics per shard and per pod.
  *   - When the imbalance ratio exceeds 1.5 (50% difference between busiest and idlest pod), the
  *     kd-tree triggers migrations of whole type-shards to less-loaded pods.
  *   - Predictive analysis (optional) detects shards trending toward overload and migrates them
  *     pre-emptively.
  *   - All shards in this strategy are migratable (no Static pinning needed since type-shards do
  *     not mix infrastructure with vehicles).
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

  private var config: StrategyConfig        = _
  private var kdTree: KdTreeLoadBalancer    = _
  private var predictor: core.actor.manager.loadbalance.prediction.PredictiveBalancer = _
  private var maxShardWeight: Double        = 50000.0

  /** Current open bucket index per entity type. */
  private val typeBucket: mutable.Map[String, Int] = mutable.Map.empty

  /** Current entity count per shard ("`{type}-shard-{N}`"). */
  private val shardCount: mutable.Map[String, Int] = mutable.Map.empty

  /** All known shard IDs (for getAllShardIds). */
  private val knownShards: mutable.Set[String] = mutable.Set.empty

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override def initialize(worldBounds: SpatialBounds, cfg: StrategyConfig): Unit = {
    config = cfg
    kdTree = new KdTreeLoadBalancer()
    predictor = new core.actor.manager.loadbalance.prediction.PredictiveBalancer(
      loadThreshold           = cfg.loadThreshold,
      predictionWindowSeconds = cfg.predictionWindowSeconds,
      maxSamples              = cfg.flowVectorSamples
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
    val entityType = extractType(entity.spatialEntityId)
    val bucket     = typeBucket.getOrElse(entityType, 0)
    val shardId    = s"$entityType-shard-$bucket"

    val count = shardCount.getOrElse(shardId, 0)
    if (count >= config.maxEntitiesPerShard) {
      // Current bucket full — open the next one
      val nextBucket  = bucket + 1
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
  }

  /** Returns a type-aware shard for the given position.
    * Since this strategy has no geographic partitioning, falls back to an entity-less type bucket.
    * Callers that know the entity type should use [[assignShard]] directly.
    */
  override def getShardForPosition(x: Double, y: Double): String = {
    // Without an entity ID we cannot determine the type; return a generic bucket.
    val hash = ((x * 31 + y) % 1000).toInt.abs
    s"unknown-shard-$hash"
  }

  override def registerNode(address: Address): Unit = kdTree.registerNode(address)
  override def removeNode(address: Address): Unit   = kdTree.removeNode(address)

  override def updateMetrics(metrics: ShardMetrics): Unit = {
    kdTree.updateMetrics(metrics)
    if (config.enablePrediction) predictor.recordMetrics(metrics)
  }

  /** Evaluates migration need purely by CPU load imbalance across pods.
    *
    *   1. Predictive: shards trending toward overload are moved pre-emptively.
    *   2. Reactive: kd-tree rebalance fires when max/min pod load ratio > 1.5.
    *
    * Target pod selection is purely load-based (lowest available load). There is no geographic
    * component in this strategy.
    */
  override def evaluate(): List[MigrationPlan] = {
    val migrations = mutable.ListBuffer[MigrationPlan]()

    // 1. Predictive overload detection
    if (config.enablePrediction) {
      predictor.predictOverloadedShards(maxShardWeight).foreach { result =>
        val currentNode = kdTree.getAssignment(result.shardId)
        currentNode.foreach { source =>
          val reason =
            if (result.isAlreadyOverloaded) MigrationReason.ReactiveOverload
            else MigrationReason.PredictiveOverload
          selectLeastLoaded(source).foreach { target =>
            migrations += MigrationPlan(
              shardId               = result.shardId,
              sourceNode            = source,
              targetNode            = target,
              reason                = reason,
              predictedLoadAtTarget = result.predictedLoad,
              priority              = if (result.isAlreadyOverloaded) 10 else 5
            )
          }
        }
      }
    }

    // 2. Periodic kd-tree rebalance
    if (kdTree.getImbalanceRatio > 1.5) {
      kdTree.rebalance().foreach {
        case (shardId, oldNode, newNode) if !migrations.exists(_.shardId == shardId) =>
          migrations += MigrationPlan(
            shardId    = shardId,
            sourceNode = oldNode.getOrElse(newNode),
            targetNode = newNode,
            reason     = MigrationReason.Rebalance,
            priority   = 1
          )
        case _ => ()
      }
    }

    migrations.toList.sortBy(-_.priority)
  }

  override def getAssignments: Map[String, Address] = kdTree.getAllAssignments

  override def getAllShardIds: Set[String] = knownShards.toSet

  /** All type-aware shards are migratable — there are no mixed static/dynamic buckets. */
  override def getShardType(shardId: String): ShardTypeEnum = ShardTypeEnum.Dynamic

  override def isMigratable(shardId: String): Boolean = true

  override def getStats: Map[String, Any] = {
    val perType = shardCount.keys
      .flatMap(s => extractTypeFromShardId(s).map(_ -> s))
      .groupBy(_._1)
      .map { case (t, pairs) =>
        t -> Map(
          "shards"    -> pairs.size,
          "entities"  -> pairs.map(p => shardCount.getOrElse(p._2, 0)).sum
        )
      }
    Map(
      "strategy"       -> name,
      "totalShards"    -> knownShards.size,
      "clusterNodes"   -> kdTree.nodeCount,
      "imbalanceRatio" -> kdTree.getImbalanceRatio,
      "byType"         -> perType,
      "nodeLoads"      -> kdTree.getNodeLoads.map { case (a, l) => a.toString -> l }
    )
  }

  override def shutdown(): Unit = predictor.clearAll()

  // ── Internal helpers ───────────────────────────────────────────────────────

  /** Extracts the entity type from an ID of the form `htcaid:{type};{id}`.
    * Falls back to `"unknown"` if the convention is not matched.
    */
  private def extractType(entityId: String): String = {
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
      .minByOption(_._2)
      .map(_._1)
}
