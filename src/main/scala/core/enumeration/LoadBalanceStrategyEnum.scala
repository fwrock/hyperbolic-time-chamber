package org.interscity.htc
package core.enumeration

/** Enumeration of available load balance strategies.
  *
  * Defines how actors are distributed across cluster nodes:
  *   - HYBRID: Fixed geographic cells and capacity buckets with projected-load planning
  *   - DEFAULT: Standard Pekko cluster sharding balancing
  *   - DISABLED: No additional load balancing beyond Pekko defaults
  *   - GEO_AFFINITY: Hybrid with separated static/dynamic shard namespaces and geographic affinity
  *     scoring for migration target selection
  *   - TYPE_AWARE: One shard namespace per actor type; entities of the same type share shards
  *     (`car-shard-0`, `car-shard-1`, …). Buckets are opened incrementally at creation time when
  *     the previous bucket reaches maxEntitiesPerShard. At runtime, the scalar-load planner proposes beneficial
  *     whole-shard moves; built-in weights count live entities rather than CPU load.
  */
enum LoadBalanceStrategyEnum:
  case Hybrid, Default, Disabled, GeoAffinity, TypeAware
