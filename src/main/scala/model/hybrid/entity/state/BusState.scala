package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ ActorTypeEnum, MovableStatusEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.ActorTypeEnum.Bus
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum

import scala.collection.mutable

/** Hybrid bus state supporting both MESO and MICRO simulation modes.
  *
  * Buses have additional complexity:
  *   - Passenger capacity and loading/unloading
  *   - Bus stops along the route
  *   - In MICRO mode: larger vehicle, slower acceleration, lane restrictions
  *
  * Route, path, cost, status, and reachedDestination are managed via accessor methods delegating to
  * parent MovableState fields (single source of truth).
  *
  * @param startTick
  *   Simulation start tick
  * @param label
  *   Bus line label
  * @param capacity
  *   Passenger capacity
  * @param distance
  *   Total distance traveled
  * @param countUnloadPassenger
  *   Count of unloaded passengers
  * @param countUnloadReceived
  *   Count of unload confirmations received
  * @param busStops
  *   Map of bus stop IDs to node IDs
  * @param numberOfPorts
  *   Number of boarding doors
  * @param people
  *   Map of passenger IDs to identifiers
  * @param currentPathPosition
  *   Current position in path
  * @param origin
  *   Origin node ID
  * @param destination
  *   Destination node ID
  * @param actorType
  *   Actor type (Bus)
  * @param size
  *   Vehicle size
  * @param currentSimulationMode
  *   Current mode (MESO or MICRO)
  * @param microState
  *   Optional microscopic bus state
  */
case class BusState(
  // ========== Meso fields ==========
  override val startTick: Tick,
  val label: String,
  val capacity: Int,
  var distance: Double = 0.0,
  var countUnloadPassenger: Int = 0,
  var countUnloadReceived: Int = 0,
  var busStops: Map[String, String],
  val numberOfPorts: Int,
  val people: mutable.Map[String, Identify] = mutable.Map[String, Identify](),
  var currentPathPosition: Int = 0,
  override val origin: String,
  override val destination: String,
  override val actorType: ActorTypeEnum = Bus,
  override val size: Double,
  var currentSimulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  var microState: Option[MicroBusState] = None,
  var storedBestRoute: Option[List[(String, String)]] = None
) extends MovableState(
      startTick = startTick,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = size
    ) {

  def bestRoute: Option[mutable.Queue[(String, String)]] = movableBestRoute
  def bestRoute_=(v: Option[mutable.Queue[(String, String)]]): Unit = movableBestRoute = v

  def currentPath: Option[(String, String)] = movableCurrentPath
  def currentPath_=(v: Option[(String, String)]): Unit = movableCurrentPath = v

  def bestCost: Double = movableBestCost
  def bestCost_=(v: Double): Unit = movableBestCost = v

  def status: MovableStatusEnum = movableStatus
  def status_=(v: MovableStatusEnum): Unit = movableStatus = v

  def reachedDestination: Boolean = movableReachedDestination
  def reachedDestination_=(v: Boolean): Unit = movableReachedDestination = v

  def isMicroMode: Boolean = currentSimulationMode == SimulationModeEnum.MICRO
  def isMesoMode: Boolean = currentSimulationMode == SimulationModeEnum.MESO

  def activateMicroMode(initialMicroState: MicroBusState): Unit = {
    currentSimulationMode = SimulationModeEnum.MICRO
    microState = Some(initialMicroState)
  }

  def deactivateMicroMode(): Unit = {
    currentSimulationMode = SimulationModeEnum.MESO
    microState = None
  }

  def updateMicroState(newMicroState: MicroBusState): Unit =
    if (isMicroMode) microState = Some(newMicroState)

  def availableCapacity: Int = capacity - people.size
  def occupancyPercentage: Double = (people.size.toDouble / capacity.toDouble) * 100.0
}
