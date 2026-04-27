package org.interscity.htc
package core.enumeration

/** Defines the loading strategy for actor data sources.
  *
  *   - EAGER: Actors are created before simulation starts (infrastructure: nodes, links, signals).
  *   - PROGRESSIVE: Actors are created progressively during simulation, based on their startTick,
  *     within a configurable look-ahead window. Avoids overwhelming the shard coordinator at
  *     startup.
  */
enum LoadingStrategyEnum:
  case EAGER, PROGRESSIVE
