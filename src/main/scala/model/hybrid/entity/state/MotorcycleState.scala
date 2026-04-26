package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ActorTypeEnum, MovableStatusEnum, SimulationModeEnum}

import scala.collection.mutable

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
 * @param distance Total distance traveled
 * @param actorType Actor type
 * @param size Vehicle size
 * @param currentSimulationMode Current mode (MESO or MICRO)
 * @param microState Optional microscopic motorcycle state
 */
case class MotorcycleState(
                            override val startTick: Tick,
                            override val origin: String,
                            override val destination: String,
                            var distance: Double = 0,
                            override val actorType: ActorTypeEnum,
                            override val size: Double,
                            var currentSimulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
                            var microState: Option[MicroMotorcycleState] = None
                                ) extends MovableState(
  startTick = startTick,
  origin = origin,
  destination = destination,
  actorType = actorType,
  size = size
) {

  def bestRoute: Option[mutable.Queue[(String, String)]] = movableBestRoute
  def bestRoute_=(v: Option[mutable.Queue[(String, String)]]): Unit = movableBestRoute = v

  def currentNode: String = movableCurrentNode
  def currentNode_=(v: String): Unit = movableCurrentNode = v

  def bestCost: Double = movableBestCost
  def bestCost_=(v: Double): Unit = movableBestCost = v

  def currentPath: Option[(String, String)] = movableCurrentPath
  def currentPath_=(v: Option[(String, String)]): Unit = movableCurrentPath = v

  def status: MovableStatusEnum = movableStatus
  def status_=(v: MovableStatusEnum): Unit = movableStatus = v

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

