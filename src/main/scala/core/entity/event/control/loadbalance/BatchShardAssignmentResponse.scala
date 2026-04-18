package org.interscity.htc
package core.entity.event.control.loadbalance

/** Response to [[RegisterSpatialEntitiesBatchEvent]] containing all spatial shard assignments.
  *
  * Sent by [[core.actor.manager.loadbalance.LoadBalanceManager]] back to
  * [[core.actor.manager.load.CreatorLoadData]] with the spatial shard ID for each
  * registered entity. Positions are also included so that subsequent entities
  * (links, cars) can resolve node coordinates.
  *
  * @param assignments
  *   Map of entity ID → assigned shard ID (spatial partitioning)
  * @param positions
  *   Map of entity ID → position (for entities with direct spatial data, like nodes)
  * @param batchId
  *   The batch identifier from the original request (for correlation)
  * @param chunkIndex
  *   The chunk index from the original request (for correlation)
  */
case class BatchShardAssignmentResponse(
  assignments: Map[String, String],
  positions: Map[String, (Double, Double)],
  batchId: String,
  chunkIndex: Int = 0
)
