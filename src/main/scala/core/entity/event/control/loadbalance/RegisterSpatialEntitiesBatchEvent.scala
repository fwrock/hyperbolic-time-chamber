package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.control.loadbalance.SpatialEntityData
import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Batch registration event for registering multiple spatial entities with
  * [[core.actor.manager.loadbalance.LoadBalanceManager]] in a single message.
  *
  * Sent by [[core.actor.manager.load.CreatorLoadData]] during entity creation to obtain
  * spatially-aware shard assignments for a chunk of entities. The LoadBalanceManager processes all
  * entities through its strategy and replies with [[BatchShardAssignmentResponse]].
  *
  * @param entities
  *   Sequence of spatial entities to register for shard assignment
  * @param batchId
  *   Identifier for the creation batch (to correlate with the response)
  * @param chunkIndex
  *   Index of the chunk within the batch (for tracking)
  */
case class RegisterSpatialEntitiesBatchEvent(
  entities: Seq[SpatialEntityData],
  batchId: String,
  chunkIndex: Int = 0
) extends BaseEvent[DefaultBaseEventData]
