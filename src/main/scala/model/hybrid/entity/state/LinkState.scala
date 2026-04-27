package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick
import core.entity.state.BaseState

import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.hybrid.entity.state.model.LinkRegister
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.state.model.{ LaneConfig, VehicleInLane }

import scala.collection.mutable

/** Hybrid link state supporting both MESO and MICRO simulation modes.
  *
  * This state extends the mesoscopic LinkState with additional fields for microscopic simulation.
  * When simulationMode is MICRO, the link manages individual vehicle positions, velocities, and
  * lane assignments.
  *
  * Key differences from mesoscopic LinkState:
  *   - simulationMode flag determines behavior
  *   - vehiclesByLane tracks individual vehicles in MICRO mode
  *   - laneConfigurations defines lane types and restrictions
  *   - microTimeStep and microTicksPerGlobalTick manage sub-tick timing
  *   - registered set maintained for backward compatibility with MESO mode
  *
  * @param startTick
  *   Simulation start tick
  * @param reporterType
  *   Type of reporter for this link
  * @param scheduleOnTimeManager
  *   Whether to schedule on time manager
  * @param from
  *   Origin node ID
  * @param to
  *   Destination node ID
  * @param length
  *   Link length (meters)
  * @param lanes
  *   Number of lanes
  * @param speedLimit
  *   Speed limit (m/s)
  * @param capacity
  *   Maximum vehicle capacity
  * @param freeSpeed
  *   Free-flow speed (m/s)
  * @param currentSpeed
  *   Current aggregate speed (for MESO mode)
  * @param congestionFactor
  *   Congestion factor (for MESO mode)
  * @param registered
  *   Registered vehicles (for MESO mode)
  * @param simulationMode
  *   MESO or MICRO mode
  * @param microTimeStep
  *   Duration of each sub-tick (seconds)
  * @param microTicksPerGlobalTick
  *   Number of sub-ticks per global tick
  * @param vehiclesByLane
  *   Vehicles organized by lane (for MICRO mode)
  * @param laneConfigurations
  *   Configuration for each lane
  */
case class LinkState(
  startTick: Tick,
  reporterType: ReportTypeEnum = null,
  scheduleOnTimeManager: Boolean = false,
  from: String,
  to: String,
  length: Double,
  lanes: Int,
  speedLimit: Double,
  capacity: Double,
  freeSpeed: Double,
  currentSpeed: Double = 0.0,
  congestionFactor: Double = 1.0,
  registered: mutable.Set[LinkRegister] = mutable.Set(),
  simulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  microTimeStep: Double = 0.1,
  microTicksPerGlobalTick: Int = 10,
  vehiclesByLane: Map[Int, mutable.Queue[VehicleInLane]] = Map.empty,
  laneConfigurations: List[LaneConfig] = List.empty
) extends BaseState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager
    ) {

  /** Check if link is in microscopic mode */
  def isMicroMode: Boolean = simulationMode == SimulationModeEnum.MICRO

  /** Check if link is in mesoscopic mode */
  def isMesoMode: Boolean = simulationMode == SimulationModeEnum.MESO

  /** Get total number of vehicles in MICRO mode */
  def totalVehiclesInMicro: Int =
    if (isMicroMode) vehiclesByLane.values.map(_.size).sum
    else 0

  /** Get total number of vehicles in MESO mode */
  def totalVehiclesInMeso: Int =
    if (isMesoMode) registered.size
    else 0

  /** Get total number of vehicles (works in both modes) */
  def totalVehicles: Int =
    if (isMicroMode) totalVehiclesInMicro
    else totalVehiclesInMeso

  /** Get density (vehicles per meter) */
  def density: Double =
    if (length > 0) totalVehicles.toDouble / length
    else 0.0

  /** Check if link is near capacity */
  def isNearCapacity(threshold: Double = 0.8): Boolean =
    totalVehicles.toDouble / capacity > threshold

  /** Initialize lane structure for MICRO mode */
  def initializeMicroLanes(): LinkState =
    if (isMicroMode && vehiclesByLane.isEmpty) {
      val effectiveLanes = math.max(1, this.lanes)
      val lanes = (0 until effectiveLanes).map {
        laneId =>
          laneId -> mutable.Queue.empty[VehicleInLane]
      }.toMap

      val configs = if (laneConfigurations.isEmpty) {
        (0 until effectiveLanes).map {
          laneId =>
            LaneConfig(laneId = laneId)
        }.toList
      } else laneConfigurations

      this.copy(
        vehiclesByLane = lanes,
        laneConfigurations = configs
      )
    } else this
}

object LinkState {

  /** Create a hybrid link in MESO mode (default, backward compatible) */
  def createMeso(
    startTick: Tick,
    from: String,
    to: String,
    length: Double,
    lanes: Int,
    speedLimit: Double,
    capacity: Double,
    freeSpeed: Double
  ): LinkState =
    LinkState(
      startTick = startTick,
      from = from,
      to = to,
      length = length,
      lanes = lanes,
      speedLimit = speedLimit,
      capacity = capacity,
      freeSpeed = freeSpeed,
      simulationMode = SimulationModeEnum.MESO
    )

  /** Create a hybrid link in MICRO mode with initialized lane structure */
  def createMicro(
    startTick: Tick,
    from: String,
    to: String,
    length: Double,
    lanes: Int,
    speedLimit: Double,
    capacity: Double,
    freeSpeed: Double,
    microTimeStep: Double = 0.1,
    microTicksPerGlobalTick: Int = 10,
    laneConfigs: List[LaneConfig] = List.empty
  ): LinkState = {
    val state = LinkState(
      startTick = startTick,
      from = from,
      to = to,
      length = length,
      lanes = lanes,
      speedLimit = speedLimit,
      capacity = capacity,
      freeSpeed = freeSpeed,
      simulationMode = SimulationModeEnum.MICRO,
      microTimeStep = microTimeStep,
      microTicksPerGlobalTick = microTicksPerGlobalTick,
      laneConfigurations = laneConfigs
    )
    state.initializeMicroLanes()
  }
}
