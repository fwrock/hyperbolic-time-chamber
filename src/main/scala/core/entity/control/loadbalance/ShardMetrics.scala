package org.interscity.htc
package core.entity.control.loadbalance

/** Metrics collected per shard for load balancing decisions.
  *
  * @param shardId
  *   Unique identifier of the shard
  * @param entityCount
  *   Number of active entities in this shard
  * @param totalWeight
  *   Sum of computational weights of all entities
  * @param messagesPerSecond
  *   Message throughput in this shard
  * @param avgProcessingTimeNanos
  *   Average message processing time in nanoseconds
  * @param flowVector
  *   Average flow vector of movable entities (dx, dy) per second
  * @param timestamp
  *   When these metrics were collected (System.nanoTime)
  */
case class ShardMetrics(
  shardId: String,
  entityCount: Int = 0,
  totalWeight: Double = 0.0,
  messagesPerSecond: Double = 0.0,
  avgProcessingTimeNanos: Long = 0L,
  flowVector: FlowVector = FlowVector.Zero,
  timestamp: Long = System.nanoTime()
) {

  /** Normalized load score in [0.0, 1.0]. Combines entity weight and processing cost. */
  def loadScore(maxWeight: Double): Double =
    if (maxWeight <= 0.0) 0.0
    else math.min(1.0, totalWeight / maxWeight)
}
