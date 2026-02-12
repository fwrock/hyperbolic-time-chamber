package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.types.Tick

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.util.IdentifyUtil
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.model.hybrid.entity.state.LinkState
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.model.LinkRegister
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost
import org.interscity.htc.model.hybrid.util.DynamicWeightCache

/** Link actor supporting both MESO and MICRO simulation modes.
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
class Link(
  private val properties: Properties
) extends SimulationBaseActor[LinkState](
      properties = properties
    ) {
  
  /** Link cost for routing (meso compatibility).
    */
  private def cost: Double = {
    val speedFactor =
      if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }
  
  /** Car-following model for microscopic simulation.
    */
  private val carFollowingModel: CarFollowingModel = KraussModel()
  
  /** Last tick when dynamic cost was published.
    */
  private var lastCostPublishTick: Tick = 0
  
  /** Interval for publishing dynamic costs (ticks).
    * Read from configuration or use default.
    */
  private val costPublishInterval: Int = {
    try {
      com.typesafe.config.ConfigFactory.load().getInt("htc.routing.link-cost.publish-interval")
    } catch {
      case _: Exception => 10 // Default: 10 ticks
    }
  }
  
  /** Cache TTL for dynamic costs (seconds).
    */
  private val cacheTtl: Int = {
    try {
      com.typesafe.config.ConfigFactory.load().getInt("htc.routing.link-cost.cache-ttl")
    } catch {
      case _: Exception => 60 // Default: 60 seconds
    }
  }
  
  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    
    // Don't send connections immediately - do it lazily on first use
    // This avoids race conditions where nodes haven't started yet
    
    // If MICRO mode, spawn LinkMicroTimeManager
    if (state.isMicroMode) {
      initializeMicroMode()
    }
    
    // Publish initial dynamic cost
    publishDynamicCost()
    
    logInfo(s"Link initialized: mode=${state.simulationMode}, lanes=${state.lanes}, length=${state.length}m")
  }


  
  /** Initialize microscopic simulation mode.
    */
  private def initializeMicroMode(): Unit = {
    logInfo(s"Initializing MICRO mode for link ${state.from} -> ${state.to}")
    
    // Initialize lanes if not already done
    if (state.vehiclesByLane.isEmpty) {
      state.initializeMicroLanes()
    }
    
    logInfo(s"✓ MICRO mode initialized")
    logInfo(s"  - linkId: ${properties.entityId}")
    logInfo(s"  - lanes: ${state.lanes}")
    logInfo(s"  - length: ${state.length}m")
    logInfo(s"  - timeStep: ${state.microTimeStep}s")
    logInfo(s"  - ticksPerGlobalTick: ${state.microTicksPerGlobalTick}")
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: EnterLinkData => handleEnterLink(event, d)
      case d: LeaveLinkData => handleLeaveLink(event, d)
      case d: MicroStepData => handleMicroStep(event, d)
      case d: LaneChangeData => handleLaneChange(event, d)
      case d: GlobalTickEvent => handleGlobalTick(d.tick)
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
    
    logDebug(s"Vehicle ${data.actorId} registered in MICRO mode, lane $assignedLane")
    
    logInfo(s"Vehicle ${data.actorId} entered MICRO link, assigned to lane $assignedLane")
  }
  
  /** Handle vehicle leaving link.
    */
  private def handleLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    logDebug(s"Vehicle ${data.actorId} leaving link")
    
    // Unregister vehicle
    state.registered.filterInPlace(_.actorId != data.actorId)
    
    if (state.isMicroMode) {
      // Remove from micro state
      state.vehiclesByLane.foreach { case (_, queue) =>
        queue.dequeueAll(_.actorId == data.actorId)
      }
      logDebug(s"Unregistered vehicle ${data.actorId} from MICRO mode")
      
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
    
    logDebug(s"Vehicle ${data.vehicleId} micro step at position ${data.currentPosition}")
    
    // Update vehicle state directly
    state.vehiclesByLane.get(data.currentLane).foreach { queue =>
      queue.find(_.actorId == data.vehicleId).foreach { vehicle =>
        val updated = vehicle.copy(
          position = data.currentPosition,
          velocity = data.currentVelocity
        )
        queue.dequeueAll(_.actorId == data.vehicleId)
        queue.enqueue(updated)
        logDebug(s"Updated vehicle ${data.vehicleId} state directly")
      }
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
    
    // Process lane change directly
    for {
      fromQueue <- state.vehiclesByLane.get(data.fromLane)
      toQueue <- state.vehiclesByLane.get(data.toLane)
      vehicle <- fromQueue.find(_.actorId == data.vehicleId)
    } {
      fromQueue.dequeueAll(_.actorId == data.vehicleId)
      val insertIdx = toQueue.indexWhere(_.position < vehicle.position)
      if (insertIdx >= 0) toQueue.insert(insertIdx, vehicle)
      else toQueue.enqueue(vehicle)
      logInfo(s"Vehicle ${data.vehicleId} changed from lane ${data.fromLane} to ${data.toLane}")
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
    * Called by LocalTimeManager via GlobalTickEvent.
    * Link executes sub-ticks and manages microscopic updates directly.
    * Also checks if dynamic cost should be published.
    */
  private def handleGlobalTick(tick: Tick): Unit = {
    // Check if we should publish dynamic cost
    if (tick - lastCostPublishTick >= costPublishInterval) {
      publishDynamicCost()
      lastCostPublishTick = tick
    }
    
    if (state.isMicroMode) {
      logDebug(s"Executing MICRO tick $tick with ${state.microTicksPerGlobalTick} sub-ticks")
      
      // Execute all sub-ticks locally
      for (subTick <- 0 until state.microTicksPerGlobalTick) {
        executeSubTick(subTick)
      }
      
      // Check for vehicles at link end
      checkVehiclesAtLinkEnd(tick)
      
      logDebug(s"Completed MICRO tick $tick")
    }
  }
  
  /** Execute a single sub-tick for all lanes.
    */
  private def executeSubTick(subTick: Int): Unit = {
    state.vehiclesByLane.foreach { case (laneId, vehicles) =>
      if (vehicles.nonEmpty) {
        processMicroLane(laneId, vehicles, subTick)
      }
    }
  }
  
  /** Process all vehicles in a lane for one sub-tick.
    * Applies car-following model and updates vehicle states.
    */
  private def processMicroLane(
    laneId: Int,
    vehicles: scala.collection.mutable.Queue[model.hybrid.entity.state.model.VehicleInLane],
    subTick: Int
  ): Unit = {
    // Process vehicles from front to back
    for (i <- vehicles.indices) {
      val vehicle = vehicles(i)
      
      // Find leader vehicle (ahead in same lane)
      val leader = if (i > 0) Some(vehicles(i - 1)) else None
      
      // Calculate gap and leader velocity
      val (gap, leaderVel) = leader match {
        case Some(l) => 
          (l.position - vehicle.position - vehicle.vehicleLength, l.velocity)
        case None => 
          (state.length - vehicle.position, state.speedLimit / 3.6) // Free road
      }
      
      // Apply car-following model (simple Krauss-like)
      val targetVel = leader match {
        case Some(l) if gap < 50.0 => 
          // Follow leader with safe gap
          math.min(l.velocity, math.sqrt(2.0 * 4.5 * gap)) // Safe velocity
        case _ => 
          state.speedLimit / 3.6 // Free-flow speed (km/h to m/s)
      }
      
      val velChange = (targetVel - vehicle.velocity) * 0.5 * state.microTimeStep
      val newVelocity = math.max(0.0, math.min(vehicle.velocity + velChange, state.speedLimit / 3.6))
      val newPosition = vehicle.position + newVelocity * state.microTimeStep
      val newAcceleration = velChange / state.microTimeStep
      
      // Update vehicle state in queue
      vehicles(i) = vehicle.copy(
        position = newPosition,
        velocity = newVelocity,
        acceleration = newAcceleration
      )
      
      // Send update to vehicle actor via sharding
      sendMessageTo(
        entityId = vehicle.actorId,
        shardId = vehicle.shardId,
        data = MicroUpdateData(
          subTick = subTick,
          position = newPosition,
          velocity = newVelocity,
          acceleration = newAcceleration,
          currentLane = laneId,
          leaderVehicle = leader.map(_.actorId),
          gapToLeader = gap,
          leaderVelocity = leaderVel,
          safeVelocity = newVelocity
        ),
        eventType = "MicroUpdate", // Custom event type
        actorType = LoadBalancedDistributed
      )
    }
  }
  
  /** Check if any vehicles have reached the end of the link.
    * Send MicroLeaveLinkData to those vehicles.
    */
  private def checkVehiclesAtLinkEnd(tick: Tick): Unit = {
    state.vehiclesByLane.foreach { case (laneId, vehicles) =>
      val vehiclesAtEnd = vehicles.filter(_.position >= state.length)
      
      vehiclesAtEnd.foreach { vehicle =>
        // Calculate travel time (LinkRegister doesn't have tick field in hybrid model)
        val travelTime = 1L // Simplified - actual calculation would need entry tick tracking
        
        // Send leave link message
        val leaveData = MicroLeaveLinkData(
          linkId = properties.entityId,
          finalPosition = vehicle.position,
          finalVelocity = vehicle.velocity,
          travelTime = travelTime.toDouble,
          distanceTraveled = state.length,
          averageSpeed = if (travelTime > 0) state.length / travelTime else vehicle.velocity
        )
        
        sendMessageTo(
          entityId = vehicle.actorId,
          shardId = vehicle.shardId,
          data = leaveData,
          eventType = "MicroLeaveLink", // Custom event type
          actorType = LoadBalancedDistributed
        )
        
        // Remove from queue
        vehicles.dequeueAll(_.actorId == vehicle.actorId)
        
        // Remove from registered (LinkRegister uses actorId, not actorRefId)
        state.registered.filterInPlace(_.actorId != vehicle.actorId)
        
        logDebug(s"Vehicle ${vehicle.actorId} left MICRO link")
      }
    }
  }
  
  /** Calculate and publish dynamic cost to cache.
    * 
    * This reflects current traffic conditions and enables dynamic routing.
    * Published to Redis for cluster-wide access.
    */
  private def publishDynamicCost(): Unit = {
    val dynamicCost = DynamicLinkCost.fromLinkState(
      linkId = getEntityId,
      length = state.length,
      currentSpeed = state.currentSpeed,
      freeFlowSpeed = state.freeSpeed,
      vehicleCount = state.registered.size,
      capacity = state.capacity,
      congestionFactor = state.congestionFactor,
      tick = currentTick
    )
    
    DynamicWeightCache.publishCost(dynamicCost, cacheTtl) match {
      case scala.util.Success(_) =>
        logDebug(s"Published dynamic cost: weight=${dynamicCost.totalCost}, congestion=${dynamicCost.congestionFactor}, vehicles=${dynamicCost.vehicleCount}")
      case scala.util.Failure(e) =>
        logWarn(s"Failed to publish dynamic cost: ${e.getMessage}")
    }
  }
}

/** Link companion object.
  */
object Link {
  
  /** Create Link from properties.
    */
  def apply(properties: Properties): Link = {
    new Link(properties)
  }
}
