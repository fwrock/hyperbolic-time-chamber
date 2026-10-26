package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.actor.manager.loadbalance.prediction.PredictiveBalancer
import core.actor.manager.loadbalance.spatial.{ KdTreeLoadBalancer, QuadtreePartitioner, SpatialShardBuckets }
import core.entity.control.loadbalance._
import core.enumeration.ShardTypeEnum

import org.apache.pekko.actor.Address

/** Fixed initial geographic cells, stable capacity buckets and projected-load placement.
  * Live refinement requires coordinated directory rewrites; ordinary evaluation preserves IDs.
  */
class HybridStrategy extends BalancingStrategy {

  override val name: String = "hybrid"

  private var quadtree: QuadtreePartitioner = _
  private var kdTree: KdTreeLoadBalancer = _
  private var predictor: PredictiveBalancer = _
  private var strategyConfig: StrategyConfig = _
  private var maxShardWeight: Double = 10000.0
  private var buckets: SpatialShardBuckets = _

  /** Shard type classification: static infrastructure vs dynamic vehicles */
  private val shardTypeMap: scala.collection.mutable.Map[String, ShardTypeEnum] =
    scala.collection.mutable.Map.empty

  override def initialize(worldBounds: SpatialBounds, config: StrategyConfig): Unit = {
    strategyConfig = config
    shardTypeMap.clear()

    // Level 1: Geographic partitioning
    quadtree = new QuadtreePartitioner(
      bounds = worldBounds,
      maxDepth = config.maxDepth,
      maxEntitiesPerLeaf = config.maxEntitiesPerShard,
      minEntitiesPerLeaf = config.minEntitiesPerShard
    )
    quadtree.initializeDepth(config.initialSpatialDepth)
    buckets = new SpatialShardBuckets(config.maxEntitiesPerShard)

    // Level 2: Load-based assignment
    kdTree = new KdTreeLoadBalancer()

    // Predictive layer
    predictor = new PredictiveBalancer(
      loadThreshold = config.loadThreshold,
      predictionWindowSeconds = config.predictionWindowSeconds,
      maxSamples = config.flowVectorSamples
    )

    // Apply 2:1 balance if enabled
    if (config.enableTwoToOneBalance) {
      quadtree.applyTwoToOneBalance()
    }

    maxShardWeight = config.maxEntitiesPerShard.toDouble
  }

  override def assignShard(entity: SpatialEntity): String = {
    val base = quadtree.registerEntity(entity)
    val shardId = buckets.assign(entity.spatialEntityId, base)
    // Classify the entity and merge shard type
    val entityType = SpatialEntityClassification.classify(entity)
    shardTypeMap.get(shardId) match {
      case Some(existing) if existing != entityType =>
        shardTypeMap.put(shardId, ShardTypeEnum.Mixed)
      case None =>
        shardTypeMap.put(shardId, entityType)
      case _ => // Same type already registered
    }
    shardId
  }

  override def getShardForPosition(x: Double, y: Double): String =
    buckets.candidate(quadtree.getShardId(x, y))

  override def registerNode(address: Address): Unit =
    kdTree.registerNode(address)

  override def removeNode(address: Address): Unit =
    kdTree.removeNode(address)

  override def updateMetrics(metrics: ShardMetrics): Unit = {
    kdTree.updateMetrics(metrics)
    if (strategyConfig.enablePrediction) {
      predictor.recordMetrics(metrics)
    }
  }

  override def evaluate(): List[MigrationPlan] = {
    val predictions = if (strategyConfig.enablePrediction)
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

  override def getAssignments: Map[String, Address] =
    kdTree.getAllAssignments

  override def getAllShardIds: Set[String] =
    buckets.shardIds

  override def getShardEntityCounts: Map[String, Int] =
    buckets.entityCounts

  override def recordShardLocation(shardId: String, address: Address): Unit =
    kdTree.recordNodeAssignment(shardId, address)
  override def replaceLoadSnapshot(metrics: Seq[ShardMetrics], assignments: Map[String, Address], nodes: Set[Address]): Unit = {
    kdTree.replaceSnapshot(metrics, assignments, nodes)
    if (strategyConfig.enablePrediction) metrics.foreach(predictor.recordMetrics)
  }

  override def getShardType(shardId: String): ShardTypeEnum =
    shardTypeMap.getOrElse(shardId, ShardTypeEnum.Dynamic)

  override def isMigratable(shardId: String): Boolean =
    getShardType(shardId) == ShardTypeEnum.Dynamic

  override def getStats: Map[String, Any] = Map(
    "strategy" -> name,
    "shardCount" -> buckets.shardIds.size,
    "spatialCells" -> quadtree.shardCount,
    "entityCount" -> quadtree.entityCount,
    "clusterNodes" -> kdTree.nodeCount,
    "imbalanceRatio" -> kdTree.getImbalanceRatio,
    "nodeLoads" -> kdTree.getNodeLoads.map {
      case (addr, load) => addr.toString -> load
    }
  )

  override def shutdown(): Unit =
    predictor.clearAll()

}
