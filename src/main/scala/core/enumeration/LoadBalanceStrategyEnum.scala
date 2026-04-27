package org.interscity.htc
package core.enumeration

/** Enumeration of available load balance strategies.
  *
  * Defines how actors are distributed across cluster nodes:
  *   - HYBRID: Quadtree (spatial) + kd-tree (load) partitioning with predictive balancing
  *   - DEFAULT: Standard Pekko cluster sharding balancing
  *   - DISABLED: No additional load balancing beyond Pekko defaults
  *   - GEO_AFFINITY: Hybrid with separated static/dynamic shard namespaces and geographic affinity
  *     scoring for migration target selection
  *   - TYPE_AWARE: One shard namespace per actor type; entities of the same type share shards
  *     (`car-shard-0`, `car-shard-1`, …). Buckets are opened incrementally at creation time when
  *     the previous bucket reaches maxEntitiesPerShard. At runtime, kd-tree migrates overloaded
  *     type-shards between pods by CPU load.
  */
enum LoadBalanceStrategyEnum:
  case Hybrid, Default, Disabled, GeoAffinity, TypeAware
