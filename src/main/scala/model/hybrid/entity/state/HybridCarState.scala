package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.hybrid.entity.state.MovableState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ActorTypeEnum, MovableStatusEnum}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.state.MicroCarState

import scala.collection.mutable

/** Hybrid car state supporting both MESO and MICRO simulation modes.
  * 
  * This state wraps the traditional CarState and adds:
  * - currentSimulationMode: Tracks whether car is currently in MESO or MICRO
  * - microState: Optional microscopic state activated when entering MICRO links
  * 
  * Mode transitions:
  * 1. Car starts in MESO mode (default)
  * 2. When entering a MICRO link, microState is initialized and mode switches to MICRO
  * 3. When leaving a MICRO link, microState is deactivated and mode returns to MESO
  * 
  * @param startTick Simulation start tick
  * @param reporterType Type of reporter
  * @param scheduleOnTimeManager Whether to schedule on time manager
  * @param name Car identifier name
  * @param origin Origin node ID
  * @param destination Destination node ID
  * @param bestRoute Best route path
  * @param bestCost Best route cost
  * @param currentNode Current node ID
  * @param currentPath Current path segment
  * @param lastNode Last visited node
  * @param digitalRails Whether using predefined paths
  * @param distance Total distance traveled
  * @param eventCount Number of events generated
  * @param actorType Actor type enum
  * @param size Vehicle size
  * @param status Current status
  * @param currentSimulationMode Current mode (MESO or MICRO)
  * @param microState Optional microscopic state (active in MICRO links)
  */
case class HybridCarState(
  // ========== Meso fields (inherited from CarState) ==========
  override val startTick: Tick,
  override val reporterType: ReportTypeEnum = null,
  override val scheduleOnTimeManager: Boolean = true,
  name: String,
  override val origin: String,
  override val destination: String = null,
  var bestRoute: Option[mutable.Queue[(String, String)]] = None,
  var bestCost: Double = Double.MaxValue,
  var currentNode: String,
  var currentPath: Option[(String, String)] = None,
  var lastNode: String,
  var digitalRails: Boolean = false,
  var distance: Double = 0,
  var eventCount: Int = 0,
  override val actorType: ActorTypeEnum,
  override val size: Double,
  var status: MovableStatusEnum = Start,
  
  // ========== Hybrid control ==========
  var currentSimulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  
  // ========== Micro state (activated in MICRO links) ==========
  var microState: Option[MicroCarState] = None
) extends MovableState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager,
      movableBestRoute = bestRoute,
      movableCurrentPath = currentPath,
      movableCurrentNode = currentNode,
      origin = origin,
      destination = destination,
      movableBestCost = bestCost,
      movableStatus = status,
      actorType = actorType,
      size = size
    ) {
  
  /** Check if car is currently in MICRO mode */
  def isMicroMode: Boolean = currentSimulationMode == SimulationModeEnum.MICRO
  
  /** Check if car is currently in MESO mode */
  def isMesoMode: Boolean = currentSimulationMode == SimulationModeEnum.MESO
  
  /** Activate MICRO mode with initial micro state */
  def activateMicroMode(initialMicroState: MicroCarState): Unit = {
    currentSimulationMode = SimulationModeEnum.MICRO
    microState = Some(initialMicroState)
  }
  
  /** Deactivate MICRO mode and return to MESO */
  def deactivateMicroMode(): Unit = {
    currentSimulationMode = SimulationModeEnum.MESO
    microState = None
  }
  
  /** Update micro state (only valid in MICRO mode) */
  def updateMicroState(newMicroState: MicroCarState): Unit = {
    if (isMicroMode) {
      microState = Some(newMicroState)
    }
  }
  
  /** Get current position (works in both modes) */
  def getCurrentPosition: Option[Double] = {
    if (isMicroMode) microState.map(_.positionInLink)
    else None  // In MESO mode, position is not tracked continuously
  }
  
  /** Get current velocity (works in both modes) */
  def getCurrentVelocity: Option[Double] = {
    if (isMicroMode) microState.map(_.velocity)
    else None  // In MESO mode, velocity is aggregate
  }
}
