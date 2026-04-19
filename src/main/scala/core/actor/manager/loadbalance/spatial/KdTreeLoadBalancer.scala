package org.interscity.htc
package core.actor.manager.loadbalance.spatial

import core.entity.control.loadbalance.{ ClusterNodeLoad, ShardMetrics }

import org.apache.pekko.actor.Address

import scala.collection.mutable

/** kd-tree based dynamic load balancer.
  *
  * Level 2 of the hybrid partitioning: decides which cluster node each quadtree shard should live
  * on. Uses median-based splitting on workload dimensions to ensure all cluster nodes process
  * roughly equal amounts of work.
  *
  * The kd-tree partitions the "load space" (not the geographic space) so that each cluster node
  * gets a balanced share of the total computational load.
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
    shardMetricsMap.put(metrics.shardId, metrics)

  /** Registers a cluster node. */
  def registerNode(address: Address): Unit =
    clusterNodes.getOrElseUpdate(
      address,
      ClusterNodeLoad(address = address)
    )

  /** Removes a cluster node. */
  def removeNode(address: Address): Unit =
    clusterNodes.remove(address)

  /** Records where a shard currently lives (from external discovery, e.g. allocator index). */
  def recordNodeAssignment(shardId: String, address: Address): Unit =
    shardAssignment.put(shardId, address)

  /** Gets the current cluster node assignment for a shard. */
  def getAssignment(shardId: String): Option[Address] =
    shardAssignment.get(shardId)

  /** Gets all shard assignments. */
  def getAllAssignments: Map[String, Address] = shardAssignment.toMap

  /** Rebalances shard assignments across cluster nodes using a kd-tree partition.
    *
    * Steps:
    *   1. Collect all shards with their weights 2. Sort by weight (median split dimension) 3.
    *      Partition into N groups (one per cluster node) using recursive median splitting 4. Assign
    *      each group to the least-loaded node
    *
    * @return
    *   List of (shardId, oldNode, newNode) for shards that need to migrate
    */
  def rebalance(): List[(String, Option[Address], Address)] = {
    val nodes = clusterNodes.keys.toList
    if (nodes.isEmpty) return Nil

    val shards = shardMetricsMap.toList.sortBy(_._2.totalWeight)
    if (shards.isEmpty) return Nil

    // Partition shards into groups, one per node
    val groups = partitionByMedian(shards, nodes.size)

    val migrations = mutable.ListBuffer[(String, Option[Address], Address)]()

    // Assign each group to a node
    groups.zip(nodes).foreach {
      case (shardGroup, nodeAddress) =>
        shardGroup.foreach {
          case (shardId, _) =>
            val currentAssignment = shardAssignment.get(shardId)
            if (!currentAssignment.contains(nodeAddress)) {
              migrations += ((shardId, currentAssignment, nodeAddress))
              shardAssignment.put(shardId, nodeAddress)
            }
        }
    }

    // Update cluster node loads
    updateNodeLoads()

    migrations.toList
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
    val loads = mutable.Map[Address, Double]()
    shardAssignment.foreach {
      case (shardId, address) =>
        val weight = shardMetricsMap.get(shardId).map(_.totalWeight).getOrElse(0.0)
        loads.update(address, loads.getOrElse(address, 0.0) + weight)
    }
    loads.toMap
  }

  /** Get the number of registered cluster nodes. */
  def nodeCount: Int = clusterNodes.size

  // ── Internal kd-tree partition ─────────────────────────────────────────────

  /** Recursively partition shards into `n` groups using median splitting on weight.
    *
    * This is a simplified 1D kd-tree split: we sort by weight and split at the median, recurse on
    * each half until we have `n` groups.
    */
  private def partitionByMedian(
    shards: List[(String, ShardMetrics)],
    n: Int
  ): List[List[(String, ShardMetrics)]] = {
    if (n <= 1 || shards.size <= 1) return List(shards)

    // Split into two halves by weight (median split)
    val sorted = shards.sortBy(_._2.totalWeight)
    val totalWeight = sorted.map(_._2.totalWeight).sum
    val targetWeight = totalWeight / 2.0

    var accumulated = 0.0
    var splitIndex = sorted.size / 2

    // Find split point closest to balanced weight
    for (i <- sorted.indices) {
      accumulated += sorted(i)._2.totalWeight
      if (accumulated <= targetWeight) {
        splitIndex = i + 1
      }
    }

    // Ensure non-empty splits
    splitIndex = math.max(1, math.min(splitIndex, sorted.size - 1))

    val (left, right) = sorted.splitAt(splitIndex)
    val leftGroups = n / 2
    val rightGroups = n - leftGroups

    partitionByMedian(left, leftGroups) ++ partitionByMedian(right, rightGroups)
  }

  /** Update cluster node load info based on current assignments. */
  private def updateNodeLoads(): Unit = {
    val nodeLoads = getNodeLoads
    val nodeCounts = mutable.Map[Address, Int]()

    shardAssignment.foreach {
      case (_, address) =>
        nodeCounts.update(address, nodeCounts.getOrElse(address, 0) + 1)
    }

    clusterNodes.foreach {
      case (address, _) =>
        clusterNodes.update(
          address,
          ClusterNodeLoad(
            address = address,
            shardIds = shardAssignment.filter(_._2 == address).keys.toSet,
            totalWeight = nodeLoads.getOrElse(address, 0.0),
            entityCount = nodeCounts.getOrElse(address, 0),
            timestamp = System.nanoTime()
          )
        )
    }
  }
}
