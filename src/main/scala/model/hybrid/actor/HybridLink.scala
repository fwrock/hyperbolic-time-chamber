package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.types.Tick
import org.apache.pekko.actor.typed.ActorRef

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

import org.interscity.htc.model.hybrid.entity.state.HybridLinkState
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.model.hybrid.micro.manager.LinkMicroTimeManager
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}
import org.interscity.htc.model.hybrid.micro.lane.{LaneChangeModel, MobilLaneChange}

import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.model.LinkRegister
import org.interscity.htc.model.mobility.entity.event.data._
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData

/** HybridLink actor supporting both MESO and MICRO simulation modes.
  * 
  * The simulation mode is determined by the link configuration (HybridLinkState.simulationMode).
  * ALL vehicles entering this link adopt its simulation mode.
  * 
  * MESO mode:
  * - Aggregate speed calculations (existing behavior)
  * - Single-tick traversal
  * - Compatible with existing mesoscopic actors
  * 
  * MICRO mode:
  * - Spawns LinkMicroTimeManager for sub-tick execution
  * - Individual vehicle positioning and car-following
  * - Lane management and lane changes
  * - Multi-tick traversal with detailed kinematics
  * 
  * @param properties Actor properties
  */
class HybridLink(
  private val properties: Properties
) extends SimulationBaseActor[HybridLinkState](
      properties = properties
    ) {
  
  /** Link cost for routing (meso compatibility).
    */
  private def cost: Double = {
    val speedFactor =
      if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }
  
  /** Optional micro time manager (only for MICRO mode).
    */
  private var microTimeManager: Option[ActorRef[LinkMicroTimeManager.Command]] = None
  
  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    
    // If MICRO mode, spawn LinkMicroTimeManager
    if (state.isMicroMode) {
      initializeMicroMode()
    }
    
    logInfo(s"HybridLink initialized: mode=${state.simulationMode}, lanes=${state.lanes}, length=${state.length}m")
  }
  
  /** Initialize microscopic simulation mode.
    */
  private def initializeMicroMode(): Unit = {
    logInfo(s"Initializing MICRO mode for link ${state.from} -> ${state.to}")
    
    // Initialize lanes if not already done
    if (state.vehiclesByLane.isEmpty) {
      state.initializeMicroLanes()
    }
    
    // Spawn LinkMicroTimeManager
    // Note: This is simplified - actual implementation would use context.spawn
    // For now, we document the intention
    logInfo(s"LinkMicroTimeManager spawn placeholder - would spawn with:")
    logInfo(s"  - linkId: ${properties.entityId}")
    logInfo(s"  - lanes: ${state.lanes}")
    logInfo(s"  - length: ${state.length}m")
    logInfo(s"  - timeStep: ${state.microTimeStep}s")
    logInfo(s"  - ticksPerGlobalTick: ${state.microTicksPerGlobalTick}")
    
    // TODO: Actual spawn implementation
    // microTimeManager = Some(context.spawn(
    //   LinkMicroTimeManager(
    //     linkId = getActorIdentify.id,
    //     numberOfLanes = state.lanes,
    //     linkLength = state.length,
    //     microTimeStep = state.microTimeStep,
    //     ticksPerGlobalTick = state.microTicksPerGlobalTick,
    //     carFollowingModel = KraussModel(),
    //     laneChangeModel = MobilLaneChange()
    //   ),
    //   name = s"micro-tm-${getActorIdentify.id}"
    // ))
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: EnterLinkData => handleEnterLink(event, d)
      case d: LeaveLinkData => handleLeaveLink(event, d)
      case d: MicroStepData => handleMicroStep(event, d)
      case d: LaneChangeData => handleLaneChange(event, d)
      case _ =>
        logWarn(s"Event not handled: ${event.data.getClass.getSimpleName}")
    }
  }
  
  /** Handle vehicle entering link.
    * 
    * Behavior depends on simulation mode:
    * - MESO: Standard mesoscopic behavior
    * - MICRO: Initialize microscopic state, register with time manager
    */
  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    logDebug(s"Vehicle ${data.actorId} entering link (mode=${state.simulationMode})")
    
    if (state.isMicroMode) {
      handleEnterLinkMicro(event, data)
    } else {
      handleEnterLinkMeso(event, data)
    }
  }
  
  /** Handle vehicle entering in MESO mode (standard behavior).
    */
  private def handleEnterLinkMeso(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    // Register vehicle
    state.registered.add(
      LinkRegister(
        actorId = data.actorId,
        shardId = data.shardId,
        actorType = data.actorType,
        actorSize = data.actorSize,
        actorCreationType = data.actorCreationType
      )
    )
    
    // Send link info (standard meso response)
    val linkInfo = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.freeSpeed,
      linkLanes = state.lanes
    )
    
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = linkInfo,
      eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }
  
  /** Handle vehicle entering in MICRO mode.
    */
  private def handleEnterLinkMicro(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    // Register vehicle (meso compatibility)
    state.registered.add(
      LinkRegister(
        actorId = data.actorId,
        shardId = data.shardId,
        actorType = data.actorType,
        actorSize = data.actorSize,
        actorCreationType = data.actorCreationType
      )
    )
    
    // Assign lane (simple strategy: least occupied lane)
    val assignedLane = findLeastOccupiedLane()
    
    // Send micro enter link data
    val microEnterData = MicroEnterLinkData(
      linkId = properties.entityId,
      mode = SimulationModeEnum.MICRO,
      assignedLane = assignedLane,
      linkLength = state.length,
      speedLimit = state.speedLimit,
      numberOfLanes = state.lanes,
      microTimeStep = state.microTimeStep,
      ticksPerGlobalTick = state.microTicksPerGlobalTick
    )
    
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = microEnterData,
      eventType = "MicroEnterLink", // Custom event type
      actorType = LoadBalancedDistributed
    )
    
    // Register with micro time manager
    microTimeManager.foreach { tm =>
      // tm ! LinkMicroTimeManager.RegisterVehicle(
      //   vehicleId = data.actorId,
      //   lane = assignedLane,
      //   position = 0.0,
      //   velocity = 0.0, // Initial velocity (vehicle will update)
      //   vehicleLength = data.actorSize,
      //   actor = ??? // Vehicle's micro update receiver
      // )
      log.debug(s"Would register vehicle ${data.actorId} with LinkMicroTimeManager")
    }
    
    logInfo(s"Vehicle ${data.actorId} entered MICRO link, assigned to lane $assignedLane")
  }
  
  /** Handle vehicle leaving link.
    */
  private def handleLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    logDebug(s"Vehicle ${data.actorId} leaving link")
    
    // Unregister vehicle
    state.registered.filterInPlace(_.actorId != data.actorId)
    
    if (state.isMicroMode) {
      // Unregister from micro time manager
      microTimeManager.foreach { tm =>
        // tm ! LinkMicroTimeManager.UnregisterVehicle(data.actorId)
        log.debug(s"Would unregister vehicle ${data.actorId} from LinkMicroTimeManager")
      }
      
      // Send micro leave data
      val microLeaveData = MicroLeaveLinkData(
        linkId = properties.entityId,
        finalPosition = state.length,
        finalVelocity = state.currentSpeed,
        travelTime = 0.0, // Would calculate actual time
        distanceTraveled = state.length,
        averageSpeed = state.currentSpeed
      )
      
      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.shardRefId,
        data = microLeaveData,
        eventType = "MicroLeaveLink",
        actorType = LoadBalancedDistributed
      )
    } else {
      // Standard meso response
      val linkInfo = LinkInfoData(
        linkLength = state.length,
        linkCapacity = state.capacity,
        linkNumberOfCars = state.registered.size,
        linkFreeSpeed = state.freeSpeed,
        linkLanes = state.lanes
      )
      
      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.shardRefId,
        data = linkInfo,
        eventType = EventTypeEnum.ReceiveLeaveLinkInfo.toString,
        actorType = LoadBalancedDistributed
      )
    }
  }
  
  /** Handle microscopic step request from vehicle.
    */
  private def handleMicroStep(event: ActorInteractionEvent, data: MicroStepData): Unit = {
    if (!state.isMicroMode) {
      logWarn(s"Received MicroStepData but link is in MESO mode")
      return
    }
    
    log.debug(s"Vehicle ${data.vehicleId} micro step at position ${data.currentPosition}")
    
    // Forward to micro time manager
    microTimeManager.foreach { tm =>
      // tm ! LinkMicroTimeManager.UpdateVehicleState(
      //   vehicleId = data.vehicleId,
      //   position = data.currentPosition,
      //   velocity = data.currentVelocity,
      //   lane = data.currentLane
      // )
      log.debug(s"Would forward micro step to LinkMicroTimeManager")
    }
  }
  
  /** Handle lane change request.
    */
  private def handleLaneChange(event: ActorInteractionEvent, data: LaneChangeData): Unit = {
    if (!state.isMicroMode) {
      logWarn(s"Received LaneChangeData but link is in MESO mode")
      return
    }
    
    logDebug(s"Vehicle ${data.vehicleId} lane change: ${data.fromLane} -> ${data.toLane}")
    
    // Forward to micro time manager
    microTimeManager.foreach { tm =>
      // tm ! LinkMicroTimeManager.RequestLaneChange(
      //   vehicleId = data.vehicleId,
      //   fromLane = data.fromLane,
      //   toLane = data.toLane
      // )
      log.debug(s"Would forward lane change to LinkMicroTimeManager")
    }
  }
  
  /** Find least occupied lane.
    */
  private def findLeastOccupiedLane(): Int = {
    if (state.vehiclesByLane.isEmpty) {
      0 // Default to first lane
    } else {
      state.vehiclesByLane.minBy(_._2.size)._1
    }
  }
  
  /** Handle global tick (for micro mode).
    */
  def onGlobalTick(tick: Tick): Unit = {
    if (state.isMicroMode) {
      microTimeManager.foreach { tm =>
        // tm ! LinkMicroTimeManager.ExecuteGlobalTick(tick)
        log.debug(s"Would execute global tick $tick on LinkMicroTimeManager")
      }
    }
  }
}

/** HybridLink companion object.
  */
object HybridLink {
  
  /** Create HybridLink from properties.
    */
  def apply(properties: Properties): HybridLink = {
    new HybridLink(properties)
  }
}
