package org.interscity.htc
package core.entity.control.loadbalance

import org.apache.pekko.actor.Address

/** A plan to migrate a shard from one cluster node to another.
  *
  * @param shardId
  *   The shard to migrate
  * @param sourceNode
  *   Current cluster node hosting the shard
  * @param targetNode
  *   Destination cluster node
  * @param reason
  *   Why this migration was triggered
  * @param predictedLoadAtTarget
  *   Expected load at target after migration
  * @param priority
  *   Migration priority (higher = more urgent)
  * @param createdAt
  *   Timestamp when this plan was created
  */
case class MigrationPlan(
  shardId: String,
  sourceNode: Address,
  targetNode: Address,
  reason: MigrationReason,
  predictedLoadAtTarget: Double = 0.0,
  priority: Int = 0,
  createdAt: Long = System.nanoTime()
)

/** Reasons why a migration is triggered. */
enum MigrationReason:
  case PredictiveOverload
  case ReactiveOverload 
  case Rebalance        
  case NodeJoin            
  case NodeLeave