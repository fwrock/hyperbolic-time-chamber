package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.state.model.LinkRegister

import scala.collection.mutable

/** State for rail link (dedicated for subway/train transit).
  *
  * Rail links are EXCLUSIVE to subway trains - they form a separate network from road links. Cars,
  * buses, and other road vehicles CANNOT use rail links.
  *
  * Key differences from regular Link:
  *   - Higher speeds (typically 60-100 km/h)
  *   - No lane changes or overtaking
  *   - Dedicated infrastructure (no mixing with road traffic)
  *   - Gradient and curvature affect speed
  *   - Typically uses MESO mode (aggregate flow)
  *
  * @param startTick
  *   Start tick
  * @param from
  *   Origin node ID
  * @param to
  *   Destination node ID
  * @param length
  *   Link length in meters
  * @param lanes
  *   Number of tracks (typically 2 for bidirectional)
  * @param speedLimit
  *   Maximum speed in km/h
  * @param capacity
  *   Maximum trains per hour
  * @param freeSpeed
  *   Free-flow speed in km/h
  * @param railType
  *   Type of rail (SUBWAY, LIGHT_RAIL, HEAVY_RAIL)
  * @param subwayLine
  *   Subway line ID this rail belongs to
  * @param fromStation
  *   Origin station ID
  * @param toStation
  *   Destination station ID
  * @param gradient
  *   Track gradient (positive = uphill, negative = downhill)
  * @param curvature
  *   Track curvature (higher = more curved, affects speed)
  * @param simulationMode
  *   Simulation mode (typically MESO for rails)
  * @param registered
  *   Registered vehicles
  * @param scheduleOnTimeManager
  *   Schedule on time manager
  */
case class RailLinkState(
  startTick: Tick,
  from: String,
  to: String,
  length: Double,
  lanes: Int,
  speedLimit: Double,
  capacity: Double,
  freeSpeed: Double,

  // Rail-specific attributes
  railType: String = "SUBWAY",
  subwayLine: String = "",
  fromStation: String = "",
  toStation: String = "",
  gradient: Double = 0.0,
  curvature: Double = 0.0,

  // Hybrid mode support (typically MESO for rails)
  simulationMode: SimulationModeEnum = SimulationModeEnum.MESO,

  // Registration
  registered: mutable.Set[LinkRegister] = mutable.Set.empty,
  scheduleOnTimeManager: Boolean = false
) extends BaseState(
      startTick = startTick,
      scheduleOnTimeManager = scheduleOnTimeManager
    ) {

  /** Calculate effective speed considering gradient and curvature.
    *
    * Speed is reduced for:
    *   - Uphill gradients
    *   - Sharp curves
    *
    * @return
    *   Effective speed in km/h
    */
  def effectiveSpeed: Double = {
    var speed = freeSpeed

    // Reduce speed for uphill (gradient > 0)
    if (gradient > 0) {
      speed = speed * (1.0 - (gradient * 0.1)) // 10% reduction per 1% gradient
    }

    // Reduce speed for curves
    if (curvature > 0) {
      speed = speed * (1.0 - (curvature * 0.05)) // 5% reduction per curvature unit
    }

    math.max(speed, speedLimit * 0.5) // Min 50% of speed limit
  }

  /** Check if a vehicle type can use this rail link.
    *
    * Only SUBWAY vehicles can use SUBWAY rail links.
    *
    * @param vehicleType
    *   Vehicle type (e.g., "Subway", "Car")
    * @return
    *   True if vehicle can use this rail
    */
  def canAcceptVehicle(vehicleType: String): Boolean =
    railType match {
      case "SUBWAY"     => vehicleType == "Subway"
      case "LIGHT_RAIL" => vehicleType == "Subway" || vehicleType == "LightRail"
      case "HEAVY_RAIL" => vehicleType == "Train"
      case _            => false
    }
}
