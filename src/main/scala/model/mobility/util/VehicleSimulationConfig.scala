package org.interscity.htc
package model.mobility.util

import core.types.Tick
import core.util.SimulationUtil

/** Shared simulation configuration for all vehicle actors.
  *
  * Provides lazy-loaded simulation limits to prevent vehicles from running indefinitely past the
  * configured simulation end time.
  */
object VehicleSimulationConfig {

  /** MESO: the vehicle moves to the next link at the tick it leaves the previous one (`t' = t`) instead of one tick later.
    * Read from `htc.meso.immediate-node-transfer` or the `HTC_MESO_IMMEDIATE_NODE_TRANSFER` environment variable (default ON; set false to restore the one-tick dwell).
    */
  lazy val mesoImmediateNodeTransfer: Boolean =
    try com.typesafe.config.ConfigFactory.load().getBoolean("htc.meso.immediate-node-transfer")
    catch { case _: Exception => !sys.env.get("HTC_MESO_IMMEDIATE_NODE_TRANSFER").exists(_.equalsIgnoreCase("false")) }

  /** MESO: carry the tick-rounding remainder of a link traversal into the next link instead of rounding every link up (default off).
    * Read from `htc.meso.carry-rounding` or the `HTC_MESO_CARRY_ROUNDING` environment variable (default ON; set false to round every link up).
    */
  lazy val mesoCarryRounding: Boolean =
    try com.typesafe.config.ConfigFactory.load().getBoolean("htc.meso.carry-rounding")
    catch { case _: Exception => !sys.env.get("HTC_MESO_CARRY_ROUNDING").exists(_.equalsIgnoreCase("false")) }

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
  lazy val microEmissionStrategy: org.interscity.htc.model.mobility.support.emission.MicroEmissionStrategy =
    try {
      val pluginParams = SimulationUtil.loadSimulationConfig().modelPlugins.getOrElse("emission", Map.empty)
      org.interscity.htc.model.mobility.support.emission.MicroEmissionStrategy.fromConfigKey(
        pluginParams.getOrElse("microModel", "none"),
        pluginParams
      )
    } catch {
      case _: Exception => org.interscity.htc.model.mobility.support.emission.MicroEmissionStrategy.none
    }

  /** Meso emission strategy — shared across all vehicle actors (pure strategy, no state).
    * Configured via simulation.modelPlugins["emission"]["mesoModel"] (e.g. "copert").
    */
  lazy val mesoEmissionStrategy: org.interscity.htc.model.mobility.support.emission.MesoEmissionStrategy =
    try {
      val pluginParams = SimulationUtil.loadSimulationConfig().modelPlugins.getOrElse("emission", Map.empty)
      org.interscity.htc.model.mobility.support.emission.MesoEmissionStrategy.fromConfigKey(
        pluginParams.getOrElse("mesoModel", "none"),
        pluginParams
      )
    } catch {
      case _: Exception => org.interscity.htc.model.mobility.support.emission.MesoEmissionStrategy.none
    }
}
