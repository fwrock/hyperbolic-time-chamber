package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import core.entity.control.loadbalance.{ ClusterNodeLoad, ShardMetrics }

import org.apache.pekko.actor.Address

import scala.collection.mutable

/** Deterministic projected-load planner. The historical class name is retained for callers.
  * Plans do not change observed ownership; every accepted move strictly reduces squared node load.
  */
class KdTreeLoadBalancer {

  /** Current shard-to-cluster-node assignment */
  private val shardAssignment: mutable.Map[String, Address] = mutable.Map.empty

  /** Latest metrics per shard */
  private val shardMetricsMap: mutable.Map[String, ShardMetrics] = mutable.Map.empty

  /** Known cluster nodes and their loads */
  private val clusterNodes: mutable.Map[Address, ClusterNodeLoad] = mutable.Map.empty

  /** Updates metrics for a shard. */
  def updateMetrics(metrics: ShardMetrics): Unit =
    {
      require(metrics.totalWeight.isFinite && metrics.totalWeight >= 0.0 && metrics.entityCount >= 0, "invalid shard metrics")
      shardMetricsMap.put(metrics.shardId, metrics)
    }

  def replaceSnapshot(metrics: Seq[ShardMetrics], assignments: Map[String, Address], nodes: Set[Address]): Unit = {
    require(assignments.values.forall(nodes.contains) && metrics.map(_.shardId).toSet == assignments.keySet,
      "incomplete load/placement snapshot")
    require(metrics.forall(m => m.totalWeight.isFinite && m.totalWeight >= 0.0 && m.entityCount >= 0), "invalid load snapshot")
    shardMetricsMap.clear()
    metrics.foreach(updateMetrics)
    shardAssignment.clear()
    shardAssignment ++= assignments
    clusterNodes.clear()
    nodes.foreach(registerNode)
  }

  /** Registers a cluster node. */
  def registerNode(address: Address): Unit =
    clusterNodes.getOrElseUpdate(
      address,
      ClusterNodeLoad(address = address)
    )

  /** Removes a cluster node. */
  def removeNode(address: Address): Unit = {
    clusterNodes.remove(address)
    shardAssignment.filterInPlace((_, node) => node != address)
  }

  /** Records where a shard currently lives (from external discovery, e.g. allocator index). */
  def recordNodeAssignment(shardId: String, address: Address): Unit =
    shardAssignment.put(shardId, address)

  /** Gets the current cluster node assignment for a shard. */
  def getAssignment(shardId: String): Option[Address] =
    shardAssignment.get(shardId)

  /** Gets all shard assignments. */
  def getAllAssignments: Map[String, Address] = shardAssignment.toMap

  def rebalance(eligible: String => Boolean = _ => true,
    score: (String, Address, Map[Address, Double]) => Double = (_, node, loads) => -loads(node)
  ): List[(String, Option[Address], Address)] = {
    val loads = mutable.Map.from(getNodeLoads)
    val moves = mutable.ListBuffer.empty[(String, Option[Address], Address)]
    shardMetricsMap.values.toVector.sortBy(m => (-m.totalWeight, m.shardId)).foreach { metric =>
      shardAssignment.get(metric.shardId).filter(loads.contains).filter(_ => eligible(metric.shardId)).foreach { source =>
        val candidates = loads.iterator.filter { case (node, load) => node != source && metric.totalWeight > 0.0 &&
          loads(source) - load > metric.totalWeight + 1e-9 }.toMap
        candidates.keys.toVector.sortBy(node => (-score(metric.shardId, node, candidates), node.toString)).headOption.foreach { target =>
            moves += ((metric.shardId, Some(source), target))
            loads(source) -= metric.totalWeight
            loads(target) += metric.totalWeight
        }
      }
    }
    moves.toList
  }

  def improvesPlacement(shardId: String, target: Address): Boolean = {
    val loads = getNodeLoads
    shardAssignment.get(shardId).exists(source => source != target && loads.contains(target) &&
      loads.getOrElse(source, 0.0) - loads(target) > shardMetricsMap.get(shardId).fold(0.0)(_.totalWeight) + 1e-9)
  }

  /** Get the load imbalance ratio across cluster nodes.
    *
    * @return
    *   Ratio of max/min load (1.0 = perfectly balanced, higher = more imbalanced)
    */
  def getImbalanceRatio: Double = {
    if (clusterNodes.size < 2) return 1.0
    val loads = getNodeLoads.values.toList
    if (loads.isEmpty) return 1.0
    val minLoad = loads.min
    val maxLoad = loads.max
    if (minLoad < 1e-10) {
      if (maxLoad < 1e-10) 1.0
      else Double.MaxValue
    } else maxLoad / minLoad
  }

  /** Gets the total load per cluster node. */
  def getNodeLoads: Map[Address, Double] = {
    val loads = mutable.Map.from(clusterNodes.keysIterator.map(_ -> 0.0))
    shardAssignment.foreach {
      case (shardId, address) =>
        val weight = shardMetricsMap.get(shardId).map(_.totalWeight).getOrElse(0.0)
        loads.update(address, loads.getOrElse(address, 0.0) + weight)
    }
    loads.toMap
  }

  /** Get the number of registered cluster nodes. */
  def nodeCount: Int = clusterNodes.size

}
