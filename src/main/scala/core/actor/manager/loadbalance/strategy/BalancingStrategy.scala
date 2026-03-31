package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import core.entity.control.loadbalance.{ MigrationPlan, ShardMetrics, SpatialBounds, SpatialEntity }

import org.apache.pekko.actor.Address

/** Interface for load balancing strategies.
  *
  * All strategies implement the same contract, allowing the LoadBalanceManager to swap strategies
  * at configuration time without changing its core orchestration logic.
  *
  * Strategies are responsible for:
  *   - Assigning shards to entities (spatial partitioning)
  *   - Assigning shards to cluster nodes (load balancing)
  *   - Deciding when and what to migrate (predictive or reactive)
  */
trait BalancingStrategy {

  /** Strategy name for logging and configuration. */
  def name: String

  /** Initializes the strategy with world bounds and initial configuration.
    *
    * @param worldBounds
    *   The spatial bounds encompassing all entities
    * @param config
    *   Strategy-specific configuration
    */
  def initialize(worldBounds: SpatialBounds, config: StrategyConfig): Unit

  /** Assigns a shard ID for a spatial entity based on its position.
    *
    * @param entity
    *   The entity to assign
    * @return
    *   The shard ID
    */
  def assignShard(entity: SpatialEntity): String

  /** Gets the shard ID for a position.
    *
    * @return
    *   The shard ID for the given coordinates
    */
  def getShardForPosition(x: Double, y: Double): String

  /** Registers a new cluster node.
    *
    * @param address
    *   The Pekko cluster address of the node
    */
  def registerNode(address: Address): Unit

  /** Removes a cluster node (node leaving).
    *
    * @param address
    *   The Pekko cluster address leaving
    */
  def removeNode(address: Address): Unit

  /** Updates metrics for a shard.
    *
    * @param metrics
    *   The latest metrics snapshot
    */
  def updateMetrics(metrics: ShardMetrics): Unit

  /** Evaluates current state and returns a list of migration plans needed.
    *
    * This is the core decision method. Called periodically by LoadBalanceManager.
    *
    * @return
    *   List of migration plans to execute (may be empty)
    */
  def evaluate(): List[MigrationPlan]

  /** Gets the current shard-to-node assignment map. */
  def getAssignments: Map[String, Address]

  /** Gets all known shard IDs. */
  def getAllShardIds: Set[String]

  /** Gets strategy-specific statistics for monitoring. */
  def getStats: Map[String, Any]

  /** Shuts down the strategy, releasing resources. */
  def shutdown(): Unit = ()
}

/** Configuration parameters for balancing strategies. */
case class StrategyConfig(
  maxDepth: Int = 8,
  maxEntitiesPerShard: Int = 10000,
  minEntitiesPerShard: Int = 100,
  loadThreshold: Double = 0.85,
  predictionWindowSeconds: Double = 10.0,
  rebalanceIntervalSeconds: Double = 30.0,
  maxConcurrentMigrations: Int = 3,
  enablePrediction: Boolean = true,
  enableTwoToOneBalance: Boolean = true,
  flowVectorSamples: Int = 100
)
