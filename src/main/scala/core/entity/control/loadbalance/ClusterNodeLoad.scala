package org.interscity.htc
package core.entity.control.loadbalance

import org.apache.pekko.actor.Address

/** Load information for a single cluster node.
  *
  * @param address
  *   The Pekko cluster address of this node
  * @param shardIds
  *   Set of shard IDs currently hosted on this node
  * @param totalWeight
  *   Sum of computational weights across all shards on this node
  * @param entityCount
  *   Total number of entities on this node
  * @param cpuUsage
  *   Estimated CPU usage [0.0, 1.0] (from Pekko metrics if available)
  * @param memoryUsage
  *   Estimated memory usage [0.0, 1.0]
  * @param timestamp
  *   When this load snapshot was taken
  */
case class ClusterNodeLoad(
  address: Address,
  shardIds: Set[String] = Set.empty,
  totalWeight: Double = 0.0,
  entityCount: Int = 0,
  cpuUsage: Double = 0.0,
  memoryUsage: Double = 0.0,
  timestamp: Long = System.nanoTime()
) {

  /** Overall load score combining weight and resource usage. */
  def loadScore: Double = {
    val weightScore = if (totalWeight > 0) math.min(1.0, totalWeight / 10000.0) else 0.0
    0.60 * weightScore + 0.25 * cpuUsage + 0.15 * memoryUsage
  }
}
