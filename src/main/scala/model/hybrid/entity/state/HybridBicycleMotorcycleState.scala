package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.mobility.entity.state.MovableState
import org.interscity.htc.model.mobility.entity.state.enumeration.{ActorTypeEnum, MovableStatusEnum}
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum

import scala.collection.mutable

/** Hybrid bicycle state supporting both MESO and MICRO simulation modes.
  * 
  * Bicycles are vulnerable road users with unique characteristics:
  * - Lower speeds (typically 15-25 km/h)
  * - Prefer bike lanes when available
  * - Can share lanes with cars if necessary
  * - In MICRO mode: detailed positioning, safety gaps, lane preferences
  * 
  * @param startTick Simulation start tick
  * @param origin Origin node ID
  * @param destination Destination node ID
  * @param bestRoute Best route path
  * @param currentNode Current node ID
  * @param distance Total distance traveled
  * @param actorType Actor type
  * @param size Vehicle size
  * @param status Current status
  * @param currentSimulationMode Current mode (MESO or MICRO)
  * @param microState Optional microscopic bicycle state
  */
case class HybridBicycleState(
  // ========== Meso fields ==========
  override val startTick: Tick,
  override val origin: String,
  override val destination: String,
  var bestRoute: Option[mutable.Queue[(String, String)]] = None,
  var currentNode: String,
  var distance: Double = 0,
  override val actorType: ActorTypeEnum,
  override val size: Double,
  var status: MovableStatusEnum = Start,
  
  // ========== Hybrid control ==========
  var currentSimulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  
  // ========== Micro state (activated in MICRO links) ==========
  var microState: Option[MicroBicycleState] = None
) extends MovableState(
      startTick = startTick,
      movableBestRoute = bestRoute,
      movableCurrentNode = currentNode,
      origin = origin,
      destination = destination,
      movableStatus = status,
      actorType = actorType,
      size = size
    ) {
  
  def isMicroMode: Boolean = currentSimulationMode == SimulationModeEnum.MICRO
  def isMesoMode: Boolean = currentSimulationMode == SimulationModeEnum.MESO
  
  def activateMicroMode(initialMicroState: MicroBicycleState): Unit = {
    currentSimulationMode = SimulationModeEnum.MICRO
    microState = Some(initialMicroState)
  }
  
  def deactivateMicroMode(): Unit = {
    currentSimulationMode = SimulationModeEnum.MESO
    microState = None
  }
  
  def updateMicroState(newMicroState: MicroBicycleState): Unit = {
    if (isMicroMode) microState = Some(newMicroState)
  }
}

/** Hybrid motorcycle state supporting both MESO and MICRO simulation modes.
  * 
  * Motorcycles have unique advantages:
  * - Higher acceleration than cars
  * - Can filter between lanes (lane splitting)
  * - Smaller vehicle, more maneuverable
  * - In MICRO mode: aggressive behavior, lane filtering, gap acceptance
  * 
  * @param startTick Simulation start tick
  * @param origin Origin node ID
  * @param destination Destination node ID
  * @param bestRoute Best route path
  * @param currentNode Current node ID
  * @param distance Total distance traveled
  * @param actorType Actor type
  * @param size Vehicle size
  * @param status Current status
  * @param currentSimulationMode Current mode (MESO or MICRO)
  * @param microState Optional microscopic motorcycle state
  */
case class HybridMotorcycleState(
  // ========== Meso fields ==========
  override val startTick: Tick,
  override val origin: String,
  override val destination: String,
  var bestRoute: Option[mutable.Queue[(String, String)]] = None,
  var currentNode: String,
  var distance: Double = 0,
  override val actorType: ActorTypeEnum,
  override val size: Double,
  var status: MovableStatusEnum = Start,
  
  // ========== Hybrid control ==========
  var currentSimulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  
  // ========== Micro state (activated in MICRO links) ==========
  var microState: Option[MicroMotorcycleState] = None
) extends MovableState(
      startTick = startTick,
      movableBestRoute = bestRoute,
      movableCurrentNode = currentNode,
      origin = origin,
      destination = destination,
      movableStatus = status,
      actorType = actorType,
      size = size
    ) {
  
  def isMicroMode: Boolean = currentSimulationMode == SimulationModeEnum.MICRO
  def isMesoMode: Boolean = currentSimulationMode == SimulationModeEnum.MESO
  
  def activateMicroMode(initialMicroState: MicroMotorcycleState): Unit = {
    currentSimulationMode = SimulationModeEnum.MICRO
    microState = Some(initialMicroState)
  }
  
  def deactivateMicroMode(): Unit = {
    currentSimulationMode = SimulationModeEnum.MESO
    microState = None
  }
  
  def updateMicroState(newMicroState: MicroMotorcycleState): Unit = {
    if (isMicroMode) microState = Some(newMicroState)
  }
}
