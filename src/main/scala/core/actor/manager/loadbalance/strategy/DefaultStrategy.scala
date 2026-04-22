package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.entity.control.loadbalance.{ MigrationPlan, ShardMetrics, SpatialBounds, SpatialEntity }

import org.apache.pekko.actor.Address

/** Default strategy that delegates entirely to Pekko's built-in cluster sharding.
  *
  * This strategy does no spatial partitioning or predictive balancing. It uses simple hash-based
  * shard assignment (Pekko default) and lets Pekko handle shard allocation across nodes.
  *
  * Use this when:
  *   - The simulation doesn't need spatial partitioning
  *   - Pekko's default balancing is sufficient
  *   - You want minimal overhead from load balancing
  */
class DefaultStrategy extends BalancingStrategy {

  override val name: String = "default"

  private val shardIds = scala.collection.mutable.Set[String]()
  private val nodes = scala.collection.mutable.Set[Address]()

  override def initialize(worldBounds: SpatialBounds, config: StrategyConfig): Unit = ()

  override def assignShard(entity: SpatialEntity): String = {
    // Use standard hash-based shard ID (same as Pekko default)
    val shardId = (entity.spatialEntityId.hashCode % 1000).toString
    shardIds.add(shardId)
    shardId
  }

  override def getShardForPosition(x: Double, y: Double): String = {
    val hash = (x.hashCode() * 31 + y.hashCode()) % 1000
    hash.toString
  }

  override def registerNode(address: Address): Unit =
    nodes.add(address)

  override def removeNode(address: Address): Unit =
    nodes.remove(address)

  override def updateMetrics(metrics: ShardMetrics): Unit = ()

  /** Default strategy never generates migrations — Pekko handles it. */
  override def evaluate(): List[MigrationPlan] = Nil

  override def getAssignments: Map[String, Address] = Map.empty

  override def getAllShardIds: Set[String] = shardIds.toSet

  override def getStats: Map[String, Any] = Map(
    "strategy" -> name,
    "shardCount" -> shardIds.size,
    "clusterNodes" -> nodes.size,
    "note" -> "Delegating to Pekko default shard allocation"
  )
}
