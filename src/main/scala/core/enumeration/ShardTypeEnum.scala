package org.interscity.htc
package core.enumeration

/** Classifies shards by the mobility characteristics of their entities.
  *
  * This distinction is critical for load balancing:
  *   - '''Static''' shards (Links, Nodes, TrafficSignals) are geographically fixed; migrating them
  *     is generally counterproductive because they anchor the spatial partition.
  *   - '''Dynamic''' shards (Cars, Buses, Bicycles, Motorcycles) contain entities that move through
  *     the simulation and may cluster unpredictably, making them candidates for migration.
  *   - '''Mixed''' shards contain both types and require special handling: only the dynamic portion
  *     contributes to load imbalance, but the static portion pins the shard geographically.
  *
  * The [[core.actor.manager.loadbalance.LoadBalanceManager]] uses this classification to decide
  * which shards are eligible for migration when rebalancing the cluster.
  */
enum ShardTypeEnum:
  /** Infrastructure actors (Links, Nodes, TrafficSignals). Geographically fixed. */
  case Static

  /** Vehicle/movable actors (Cars, Buses, Bicycles, Motorcycles). Mobile. */
  case Dynamic

  /** Contains both static and dynamic entities. */
  case Mixed
