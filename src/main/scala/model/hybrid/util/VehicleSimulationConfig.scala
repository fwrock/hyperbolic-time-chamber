package org.interscity.htc
package model.hybrid.util

import core.types.Tick
import core.util.SimulationUtil

/** Shared simulation configuration for all vehicle actors.
  *
  * Provides lazy-loaded simulation limits to prevent vehicles from running indefinitely past the
  * configured simulation end time.
  */
object VehicleSimulationConfig {

  /** Simulation end tick - used only when extendSimulationIfPendingEventsAfterEnd is false. When
    * extension is enabled, vehicles are allowed to finish their trips naturally.
    */
  lazy val simulationEndTick: Tick =
    try {
      val config = SimulationUtil.loadSimulationConfig()
      config.duration
    } catch {
      case _: Exception => 86400L // Default: 24 hours in seconds
    }

  /** Whether the simulation should extend beyond the configured duration to allow pending vehicles
    * to complete their trips.
    */
  lazy val extendSimulationIfPendingEventsAfterEnd: Boolean =
    try {
      val config = SimulationUtil.loadSimulationConfig()
      config.extendSimulationIfPendingEventsAfterEnd
    } catch {
      case _: Exception => false
    }

  /** Micro emission strategy — shared across all vehicle actors (pure strategy, no state).
    * Configured via simulation.modelPlugins["emission"]["microModel"] (e.g. "virginia_tech_micro").
    */
  lazy val microEmissionStrategy: org.interscity.htc.model.hybrid.support.emission.MicroEmissionStrategy =
    try {
      val pluginParams = SimulationUtil.loadSimulationConfig().modelPlugins.getOrElse("emission", Map.empty)
      org.interscity.htc.model.hybrid.support.emission.MicroEmissionStrategy.fromConfigKey(
        pluginParams.getOrElse("microModel", "none"),
        pluginParams
      )
    } catch {
      case _: Exception => org.interscity.htc.model.hybrid.support.emission.MicroEmissionStrategy.none
    }

  /** Meso emission strategy — shared across all vehicle actors (pure strategy, no state).
    * Configured via simulation.modelPlugins["emission"]["mesoModel"] (e.g. "copert").
    */
  lazy val mesoEmissionStrategy: org.interscity.htc.model.hybrid.support.emission.MesoEmissionStrategy =
    try {
      val pluginParams = SimulationUtil.loadSimulationConfig().modelPlugins.getOrElse("emission", Map.empty)
      org.interscity.htc.model.hybrid.support.emission.MesoEmissionStrategy.fromConfigKey(
        pluginParams.getOrElse("mesoModel", "none"),
        pluginParams
      )
    } catch {
      case _: Exception => org.interscity.htc.model.hybrid.support.emission.MesoEmissionStrategy.none
    }
}
