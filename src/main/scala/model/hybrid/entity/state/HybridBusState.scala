package org.interscity.htc
package model.hybrid.entity.state

import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.model.hybrid.entity.state.MovableState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ActorTypeEnum, MovableStatusEnum}
import org.interscity.htc.model.hybrid.entity.state.enumeration.ActorTypeEnum.Bus
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Start
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum

import scala.collection.mutable

/** Hybrid bus state supporting both MESO and MICRO simulation modes.
  * 
  * Buses have additional complexity:
  * - Passenger capacity and loading/unloading
  * - Bus stops along the route
  * - In MICRO mode: larger vehicle, slower acceleration, lane restrictions
  * 
  * @param startTick Simulation start tick
  * @param label Bus line label
  * @param capacity Passenger capacity
  * @param distance Total distance traveled
  * @param countUnloadPassenger Count of unloaded passengers
  * @param countUnloadReceived Count of unload confirmations received
  * @param busStops Map of bus stop IDs to node IDs
  * @param numberOfPorts Number of boarding doors
  * @param people Map of passenger IDs to identifiers
  * @param bestRoute Best route path
  * @param currentPath Current path segment
  * @param currentPathPosition Current position in path
  * @param origin Origin node ID
  * @param destination Destination node ID
  * @param bestCost Best route cost
  * @param status Current status
  * @param reachedDestination Whether bus reached destination
  * @param actorType Actor type (Bus)
  * @param size Vehicle size
  * @param currentSimulationMode Current mode (MESO or MICRO)
  * @param microState Optional microscopic bus state
  */
case class HybridBusState(
  // ========== Meso fields (inherited from BusState) ==========
  override val startTick: Tick,
  val label: String,
  val capacity: Int,
  var distance: Double = 0.0,
  var countUnloadPassenger: Int = 0,
  var countUnloadReceived: Int = 0,
  var busStops: Map[String, String],
  val numberOfPorts: Int,
  val people: mutable.Map[String, Identify] = mutable.Map[String, Identify](),
  var bestRoute: Option[mutable.Queue[(String, String)]] = None,
  var currentPath: Option[(String, String)] = None,
  var currentPathPosition: Int = 0,
  override val origin: String,
  override val destination: String,
  var bestCost: Double = Double.MaxValue,
  var status: MovableStatusEnum = Start,
  var reachedDestination: Boolean = false,
  override val actorType: ActorTypeEnum = Bus,
  override val size: Double,
  
  // ========== Hybrid control ==========
  var currentSimulationMode: SimulationModeEnum = SimulationModeEnum.MESO,
  
  // ========== Micro state (activated in MICRO links) ==========
  var microState: Option[MicroBusState] = None
) extends MovableState(
      startTick = startTick,
      movableBestRoute = bestRoute,
      movableCurrentPath = currentPath,
      movableCurrentNode = null,
      origin = origin,
      destination = destination,
      movableBestCost = bestCost,
      movableStatus = status,
      movableReachedDestination = reachedDestination,
      actorType = actorType,
      size = size
    ) {
  
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
  
  def updateMicroState(newMicroState: MicroBusState): Unit = {
    if (isMicroMode) microState = Some(newMicroState)
  }
  
  def availableCapacity: Int = capacity - people.size
  def occupancyPercentage: Double = (people.size.toDouble / capacity.toDouble) * 100.0
}
