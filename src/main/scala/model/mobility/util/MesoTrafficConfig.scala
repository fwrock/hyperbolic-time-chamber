package org.interscity.htc
package model.mobility.util

import core.api.SimulatorSettingsRegistry

/** Scenario-owned MESO conventions; defaults preserve earlier experiments. */
object MesoTrafficConfig {

  /** Entry speed may use pre-entry occupancy, avoiding self-congestion on one-slot links. Routing
    * publications still describe the occupied link seen by a prospective next entrant.
    */
  def includeEnteringVehicle: Boolean =
    SimulatorSettingsRegistry.effective.getString("htc.mobility.meso.entry-density") match {
      case "inclusive"         => true
      case "existing-vehicles" => false
      case other => throw new IllegalArgumentException(s"Unknown MESO entry-density '$other'")
    }

  /** Bicycle MESO cruising speed, in metres per second. */
  def bicycleSpeedMps: Double = {
    val value = SimulatorSettingsRegistry.effective.getDouble("htc.mobility.meso.bicycle-speed-mps")
    require(value.isFinite && value > 0, "MESO bicycle speed must be positive finite m/s")
    value
  }

  /** Separate fixed-speed bicycle distance costs from motor-traffic congestion when requested. */
  def useDynamicWeights(mode: String): Boolean =
    SimulatorSettingsRegistry.effective.getString(
      "htc.mobility.mode-choice.private-cost-model"
    ) match {
      case "shared-dynamic"             => true
      case "car-dynamic-bicycle-static" => mode != "bicycle"
      case other => throw new IllegalArgumentException(s"Unknown private cost model '$other'")
    }
}
