package org.interscity.htc
package model.mobility.entity.state

import core.types.Tick

import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.mobility.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum, SimulationModeEnum }

import scala.collection.mutable

/** Hybrid motorcycle state supporting both MESO and MICRO simulation modes.
  *
  * Motorcycles have unique advantages:
  *   - Higher acceleration than cars
  *   - Can filter between lanes (lane splitting)
  *   - Smaller vehicle, more maneuverable
  *   - In MICRO mode: aggressive behavior, lane filtering, gap acceptance
  *
  * @param startTick
  *   Simulation start tick
  * @param origin
  *   Origin node ID
  * @param destination
  *   Destination node ID
  * @param distance
  *   Total distance traveled
  * @param actorType
  *   Actor type
  * @param size
  *   Vehicle size
  * @param currentSimulationMode
  *   Current mode (MESO or MICRO)
  * @param microState
  *   Optional microscopic motorcycle state
  */
case class MotorcycleState(
  override val startTick: Tick,
  override val origin: Long,
  override val destination: Long,
  var distance: Double = 0,
  override val actorType: ActorTypeEnum,
  override val size: Double,
  var currentSimulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  var microState: Option[MicroMotorcycleState] = None,
  var mesoRoundingCredit: Double = 0.0,
  var mesoLastExitTick: Long = -1L,
  /** Length of the MESO link the vehicle is on, kept so a leave that the Link does not answer can still be completed. */
  var mesoLinkLength: Double = 0.0,
  /** Whether the current MESO link will answer this vehicle's leave; set from the entry reply, so a lost value only restores the reply. */
  var leaveAckRequired: Boolean = true,
  /** The Link took the vehicle off the link when the Node granted the crossing, so it sends no leave. */
  var leaveHandledByLink: Boolean = false,
  /** The Link held the entry reply until the exit tick, so the exit chain ran inline. */
  var linkOwnedExit: Boolean = false
) extends MovableState(
      startTick = startTick,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = size
    ) with org.interscity.htc.model.mobility.support.car.MesoTimingState {

  def bestRoute: Option[mutable.Queue[(Long, Long)]] = movableBestRoute
  def bestRoute_=(v: Option[mutable.Queue[(Long, Long)]]): Unit = movableBestRoute = v

  def currentNode: Long = movableCurrentNode
  def currentNode_=(v: Long): Unit = movableCurrentNode = v

  def bestCost: Double = movableBestCost
  def bestCost_=(v: Double): Unit = movableBestCost = v

  def currentPath: Option[(Long, Long)] = movableCurrentPath
  def currentPath_=(v: Option[(Long, Long)]): Unit = movableCurrentPath = v

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

  def updateMicroState(newMicroState: MicroMotorcycleState): Unit =
    if (isMicroMode) microState = Some(newMicroState)
}
