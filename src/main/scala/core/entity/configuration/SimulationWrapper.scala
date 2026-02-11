package org.interscity.htc
package core.entity.configuration

/**
 * Wrapper for simulation.json files that have the structure:
 * {
 *   "simulation": { ... }
 * }
 */
case class SimulationWrapper(
  simulation: Simulation
)
