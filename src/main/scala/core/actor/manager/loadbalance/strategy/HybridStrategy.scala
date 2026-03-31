package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.actor.manager.loadbalance.prediction.PredictiveBalancer
import core.actor.manager.loadbalance.spatial.{ KdTreeLoadBalancer, QuadtreePartitioner }
import core.entity.control.loadbalance._

import org.apache.pekko.actor.Address

/** Hybrid balancing strategy combining:
  *
  *   - Level 1: Quadtree (spatial/geographic) — stable shard IDs for infrastructure
  *   - Level 2: kd-tree (load) — dynamic assignment of shards to cluster nodes
  *   - Predictive: Flow vector analysis to anticipate overload
  *   - 2:1 Balance: Ensures smooth density transitions between shards
  *
  * This is the high-performance strategy designed for 10M+ actors. It maximizes data locality
  * (geographically close actors share shards) while maintaining load balance across cluster nodes.
  */
class HybridStrategy extends BalancingStrategy {

  override val name: String = "hybrid"

  private var quadtree: QuadtreePartitioner = _
  private var kdTree: KdTreeLoadBalancer = _
  private var predictor: PredictiveBalancer = _
  private var strategyConfig: StrategyConfig = _
  private var maxShardWeight: Double = 10000.0

  override def initialize(worldBounds: SpatialBounds, config: StrategyConfig): Unit = {
    strategyConfig = config

    // Level 1: Geographic partitioning
    quadtree = new QuadtreePartitioner(
      bounds = worldBounds,
      maxDepth = config.maxDepth,
      maxEntitiesPerLeaf = config.maxEntitiesPerShard,
      minEntitiesPerLeaf = config.minEntitiesPerShard
    )

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

  override def assignShard(entity: SpatialEntity): String =
    quadtree.registerEntity(entity)

  override def getShardForPosition(x: Double, y: Double): String =
    quadtree.getShardId(x, y)

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
    val migrations = scala.collection.mutable.ListBuffer[MigrationPlan]()

    // 1. Predictive analysis — check for imminent overloads
    if (strategyConfig.enablePrediction) {
      val predictions = predictor.predictOverloadedShards(maxShardWeight)
      predictions.foreach {
        result =>
          val currentAssignment = kdTree.getAssignment(result.shardId)
          if (currentAssignment.isDefined) {
            val reason =
              if (result.isAlreadyOverloaded) MigrationReason.ReactiveOverload
              else MigrationReason.PredictiveOverload

            // Find least-loaded node
            val nodeLoads = kdTree.getNodeLoads
            val leastLoaded = nodeLoads.minByOption(_._2)

            leastLoaded.foreach {
              case (targetNode, _) if !currentAssignment.contains(targetNode) =>
                migrations += MigrationPlan(
                  shardId = result.shardId,
                  sourceNode = currentAssignment.get,
                  targetNode = targetNode,
                  reason = reason,
                  predictedLoadAtTarget = result.predictedLoad,
                  priority = if (result.isAlreadyOverloaded) 10 else 5
                )
              case _ => () // Already on least-loaded node
            }
          }
      }
    }

    // 2. Periodic kd-tree rebalancing if imbalance exceeds threshold
    val imbalance = kdTree.getImbalanceRatio
    if (imbalance > 1.5) { // More than 50% imbalance
      val rebalanceMigrations = kdTree.rebalance()
      rebalanceMigrations.foreach {
        case (shardId, oldNode, newNode) =>
          // Avoid duplicates from predictive analysis
          if (!migrations.exists(_.shardId == shardId)) {
            migrations += MigrationPlan(
              shardId = shardId,
              sourceNode = oldNode.getOrElse(newNode),
              targetNode = newNode,
              reason = MigrationReason.Rebalance,
              priority = 1
            )
          }
      }
    }

    // 3. Adaptively refine quadtree based on current entity distribution
    quadtree.refine()
    if (strategyConfig.enableTwoToOneBalance) {
      quadtree.applyTwoToOneBalance()
    }

    migrations.toList.sortBy(-_.priority)
  }

  override def getAssignments: Map[String, Address] =
    kdTree.getAllAssignments

  override def getAllShardIds: Set[String] =
    quadtree.getAllShardIds

  override def getStats: Map[String, Any] = Map(
    "strategy" -> name,
    "shardCount" -> quadtree.shardCount,
    "entityCount" -> quadtree.entityCount,
    "clusterNodes" -> kdTree.nodeCount,
    "imbalanceRatio" -> kdTree.getImbalanceRatio,
    "nodeLoads" -> kdTree.getNodeLoads.map { case (addr, load) => addr.toString -> load }
  )

  override def shutdown(): Unit = {
    predictor.clearAll()
  }
}
