package org.interscity.htc
package core.enumeration

/**
 * Classifies actors by their lifecycle behavior in the simulation.
 *
 * - STATIC: Infrastructure actors that exist for the entire simulation duration
 *   (e.g., nodes, links, traffic signals, bus stops, subway stations).
 *   They are created once and never destroyed.
 *
 * - DYNAMIC: Transient actors that are created and destroyed during the simulation
 *   based on demand (e.g., cars, buses, persons, bicycles, motorcycles).
 *   They have a startTick and may terminate after completing their activity.
 *
 * This classification is orthogonal to LoadingStrategyEnum (EAGER/PROGRESSIVE)
 * and enables future optimizations such as:
 * - Selective persistence (only persist dynamic actor state changes)
 * - Memory management (reclaim resources from completed dynamic actors)
 * - Differential reporting (separate metrics for infrastructure vs. movable entities)
 * - Warm-start scenarios (reuse static actors, regenerate dynamic ones)
 */
enum EntityLifecycleEnum:
  case STATIC, DYNAMIC
