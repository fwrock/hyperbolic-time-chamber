package org.interscity.htc
package core.enumeration

/** Enumeration of available load balance strategies.
  *
  * Defines how actors are distributed across cluster nodes:
  *   - HYBRID: Quadtree (spatial) + kd-tree (load) partitioning with predictive balancing
  *   - DEFAULT: Standard Pekko cluster sharding balancing
  *   - DISABLED: No additional load balancing beyond Pekko defaults
  */
enum LoadBalanceStrategyEnum:
  case Hybrid, Default, Disabled
