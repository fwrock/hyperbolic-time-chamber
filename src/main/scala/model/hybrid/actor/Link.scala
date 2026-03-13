package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.types.Tick
import core.entity.event.SpontaneousEvent

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
import org.interscity.htc.model.hybrid.entity.state.model.{DynamicLinkCost, LinkRegister, VehicleInLane}
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.util.DynamicWeightCache

import scala.collection.mutable

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

  private var summaryTick: Tick = Long.MinValue
  private var tickLoaded: Int = 0
  private var tickInserted: Int = 0
  private var tickArrived: Int = 0
  private var tickTravelTimeSum: Double = 0.0
  private var tickProcessingDurationMs: Long = 0L
  private var cumulativeLoadedVehicles: Long = 0L
  private var cumulativeLoaded: Long = 0L
  private var cumulativeArrived: Long = 0L
  private var cumulativeDiscarded: Long = 0L

  private val vehicleEntryTick: mutable.Map[String, Tick] = mutable.Map.empty
  private val vehicleWaitingSeconds: mutable.Map[String, Double] = mutable.Map.empty

  /** Whether this link is currently scheduled for micro ticks. */
  private var microTickScheduled: Boolean = false
  
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
    
    logDebug(s"Link initialized: mode=${state.simulationMode}, lanes=${state.lanes}, length=${state.length}m")
  }


  
  /** Initialize microscopic simulation mode.
    */
  private def initializeMicroMode(): Unit = {
    logDebug(s"Initializing MICRO mode for link ${state.from} -> ${state.to}")
    
    // Initialize lanes if not already done
    if (state.vehiclesByLane.isEmpty) {
      state = state.initializeMicroLanes()
    }

    logDebug(s"✓ MICRO mode initialized")
    logDebug(s"  - linkId: ${getEntityId}")
    logDebug(s"  - lanes: ${state.lanes}")
    logDebug(s"  - length: ${state.length}m")
    logDebug(s"  - timeStep: ${state.microTimeStep}s")
    logDebug(s"  - ticksPerGlobalTick: ${state.microTicksPerGlobalTick}")
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: EnterLinkData => handleEnterLink(event, d)
      case d: LeaveLinkData => handleLeaveLink(event, d)
      case _ =>
        logWarn(s"Event not handled: ${event.data.getClass.getSimpleName}")
    }
  }

  override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (!state.isMicroMode) {
      logInfo(s"Link ${getEntityId} in MESO mode (registered=${state.registered.size}) received spontaneous event - unregistering")
      microTickScheduled = false
      onFinishSpontaneous(None)
      return
    }

    val vehicleCount = state.totalVehiclesInMicro
    val hasVehicles = vehicleCount > 0
    val registeredCount = state.registered.size
    
    if (!hasVehicles) {
      logInfo(s"Link ${getEntityId} has no vehicles (micro=$vehicleCount, registered=$registeredCount) at tick $currentTick - unregistering from TimeManager")
      microTickScheduled = false // Important!!
      onFinishSpontaneous(None)
      return
    }

    handleGlobalTick(currentTick)

    val hasVehiclesAfterTick = vehicleCount > 0
    if (hasVehiclesAfterTick) {
      onFinishSpontaneous(Some(currentTick + 1))
    } else {
      microTickScheduled = false
      logInfo(s"Link ${getEntityId} became empty during tick $currentTick - stopping scheduling now")
      onFinishSpontaneous(None)
    }
  }
  
  /** Handle vehicle entering link.
    * 
    * Behavior depends on simulation mode:
    * - MESO: Standard mesoscopic behavior
    * - MICRO: Initialize microscopic state, register with time manager
    */
  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    ensureSummaryTick(currentTick)
    logDebug(s"Vehicle ${data.actorId} entering link (mode=${state.simulationMode})")
    
    // Report vehicle entering link
    report(
      data = Map(
        "event_type" -> "vehicle_entered_link",
        "link_id" -> getEntityId,
        "vehicle_id" -> data.actorId,
        "vehicle_type" -> data.actorType,
        "link_length" -> state.length,
        "simulation_mode" -> state.simulationMode.toString,
        "current_congestion" -> state.congestionFactor,
        "vehicles_in_link" -> state.registered.size,
        "tick" -> currentTick
      ),
      label = "link_vehicle_entered"
    )
    
    if (state.isMicroMode) {
      handleEnterLinkMicro(event, data)
    } else {
      handleEnterLinkMeso(event, data)
    }
  }
  
  /** Handle vehicle entering in MESO mode (standard behavior).
    */
  private def handleEnterLinkMeso(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    if (state.registered.exists(_.actorId == data.actorId)) {
      val duplicateInfo = LinkInfoData(
        linkLength = state.length,
        linkCapacity = state.capacity,
        linkNumberOfCars = state.registered.size,
        linkFreeSpeed = state.freeSpeed,
        linkLanes = state.lanes
      )

      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.shardRefId,
        data = duplicateInfo,
        eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
        actorType = LoadBalancedDistributed
      )
      logDebug(s"Duplicate MESO enter ignored for vehicle ${data.actorId}")
      return
    }

    tickLoaded += 1
    cumulativeLoadedVehicles += 1
    vehicleEntryTick.put(data.actorId, currentTick)
    vehicleWaitingSeconds.getOrElseUpdate(data.actorId, 0.0)

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
    onVehicleInserted()
    
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
    findVehicleLane(data.actorId) match {
      case Some(existingLane) =>
        sendMicroEnterAck(event, existingLane)
        if (!microTickScheduled) {
          microTickScheduled = true
          scheduleEvent(currentTick + 1)
          logDebug(s"Re-activated micro scheduling at tick ${currentTick + 1}")
        }
        logDebug(s"Duplicate MICRO enter ignored for vehicle ${data.actorId} on lane $existingLane")
        return
      case None =>
    }

    tickLoaded += 1
    cumulativeLoadedVehicles += 1
    vehicleEntryTick.put(data.actorId, event.tick)
    vehicleWaitingSeconds.getOrElseUpdate(data.actorId, 0.0)

    // Register vehicle (meso compatibility)
    if (!state.registered.exists(_.actorId == data.actorId)) {
      state.registered.add(
        LinkRegister(
          actorId = data.actorId,
          shardId = data.shardId,
          actorType = data.actorType,
          actorSize = data.actorSize,
          actorCreationType = data.actorCreationType
        )
      )
    }
    
    // Ensure micro lanes are initialized
    if (state.vehiclesByLane.isEmpty) {
      state = state.initializeMicroLanes()
    }

    // Assign lane (simple strategy: least occupied lane)
    val assignedLane = findLeastOccupiedLane()

    // Track vehicle in micro lane state (entry tick from event)
    val entryTick = event.tick
    val vehicle = VehicleInLane(
      actorId = data.actorId,
      shardId = data.shardId,
      position = 0.0,
      velocity = 0.0,
      acceleration = 0.0,
      vehicleLength = data.actorSize,
      entryTick = entryTick
    )

    state.vehiclesByLane.get(assignedLane).foreach { queue =>
      val insertIdx = queue.indexWhere(_.position < vehicle.position)
      if (insertIdx >= 0) queue.insert(insertIdx, vehicle) else queue.enqueue(vehicle)
    }
    onVehicleInserted()

    sendMicroEnterAck(event, assignedLane)

    logDebug(s"Vehicle ${data.actorId} registered in MICRO mode, lane $assignedLane")

    // Schedule micro ticks on demand
    if (!microTickScheduled) {
      microTickScheduled = true
      scheduleEvent(entryTick + 1)
      logDebug(s"Activated micro scheduling at tick ${entryTick + 1}")
    }
  }

  private def sendMicroEnterAck(event: ActorInteractionEvent, lane: Int): Unit = {
    val microEnterData = MicroEnterLinkData(
      linkId = getEntityId,
      mode = SimulationModeEnum.MICRO,
      assignedLane = lane,
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
      eventType = "MicroEnterLink",
      actorType = LoadBalancedDistributed
    )
  }

  private def findVehicleLane(actorId: String): Option[Int] = {
    state.vehiclesByLane.collectFirst {
      case (laneId, queue) if queue.exists(_.actorId == actorId) => laneId
    }
  }
  
  /** Handle vehicle leaving link.
    */
  private def handleLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    ensureSummaryTick(currentTick)
    logDebug(s"Vehicle ${data.actorId} leaving link")

    val wasRegistered = state.registered.exists(_.actorId == data.actorId)
    val vehiclesRemaining = math.max(0, state.registered.size - (if (wasRegistered) 1 else 0))
    
    // Report vehicle leaving link
    report(
      data = Map(
        "event_type" -> "vehicle_left_link",
        "link_id" -> getEntityId,
        "vehicle_id" -> data.actorId,
        "vehicle_type" -> data.actorType.toString,
        "link_length" -> state.length,
        "vehicles_remaining" -> vehiclesRemaining,
        "tick" -> currentTick
      ),
      label = "link_vehicle_left"
    )
    
    // Unregister vehicle
    state.registered.filterInPlace(_.actorId != data.actorId)
    val entryTick = vehicleEntryTick.get(data.actorId) match {
      case Some(tick) => tick
      case None => -1
    }
    vehicleEntryTick.remove(data.actorId)
    vehicleWaitingSeconds.remove(data.actorId)
    if (wasRegistered) {
      onVehicleArrived(travelTime = 0.0)
    }
    
    if (state.isMicroMode) {
      sendLeaveLinkMicro(event, data, entryTick)
    } else {
      sendLeaveLinkDataMeso(event)
    }
  }

  private def sendLeaveLinkMicro(event: ActorInteractionEvent, data: LeaveLinkData, entryTick: Long): Unit = {
    val vehicle = state.vehiclesByLane.flatMap { case (_, queue) =>
      queue.find(_.actorId == data.actorId)
    }.headOption
    state.vehiclesByLane.foreach { case (_, queue) =>
      queue.dequeueAll(_.actorId == data.actorId)
    }
    logDebug(s"Unregistered vehicle ${data.actorId} from MICRO mode")

    // Send micro leave data
    val microLeaveData = MicroLeaveLinkData(
      linkId = getEntityId,
      finalPosition = state.length,
      finalVelocity = state.currentSpeed,
      travelTime = math.max(1L, currentTick - entryTick + 1),
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

    if (state.totalVehiclesInMicro == 0 && microTickScheduled) {
      microTickScheduled = false
      logDebug("MICRO link is now empty; stopping scheduling")
    }
  }

  private def sendLeaveLinkDataMeso(event: ActorInteractionEvent): Unit = {
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
    val processingStartedAt = System.nanoTime()
    ensureSummaryTick(tick)

    // Check if we should publish dynamic cost
    if (tick - lastCostPublishTick >= costPublishInterval) {
      publishDynamicCost()
      lastCostPublishTick = tick
    }
    
    if (state.isMicroMode) {
      if (state.totalVehiclesInMicro == 0) {
        logDebug(s"Skipping MICRO tick $tick (no vehicles)")
        return
      }
      logDebug(s"Executing MICRO tick $tick with ${state.microTicksPerGlobalTick} sub-ticks")
      
      // Execute all sub-ticks locally
      for (subTick <- 0 until state.microTicksPerGlobalTick) {
        executeSubTick(subTick, tick)
      }
      logDebug(s"Completed MICRO tick $tick")
    }

    tickProcessingDurationMs = (System.nanoTime() - processingStartedAt) / 1000000L
    emitSumoSummaryStep(tick)
  }
  
  /** Execute a single sub-tick for all lanes.
    */
  private def executeSubTick(subTick: Int, tick: Tick): Unit = {
    state.vehiclesByLane.foreach { case (laneId, vehicles) =>
      if (vehicles.nonEmpty) {
        processMicroLane(laneId, vehicles, subTick, tick)
      }
    }
  }
  
  /** Process all vehicles in a lane for one sub-tick.
    * Applies car-following model and updates vehicle states.
    */
  private def processMicroLane(
                                laneId: Int,
                                vehicles: mutable.Queue[VehicleInLane],
                                subTick: Int,
                                tick: Tick,
  ): Unit = {
    // Process vehicles from front to back
    for (i <- vehicles.indices) {
      val vehicle = vehicles(i)

      // Find leader vehicle (ahead in same lane)
      val leader = if (i > 0) Some(vehicles(i - 1)) else None

      // Calculate gap and leader velocity
      val (rawGap, leaderVel) = leader match {
        case Some(l) =>
          (l.position - vehicle.position - vehicle.vehicleLength, l.velocity)
        case None =>
          (state.length - vehicle.position, state.speedLimit / 3.6) // Free road
      }
      val gap = math.max(0.1, rawGap)

      // Apply car-following model (simple Krauss-like)
      val targetVel = leader match {
        case Some(l) if gap < 50.0 =>
          // Follow leader with safe gap
          math.min(l.velocity, math.sqrt(2.0 * 4.5 * gap)) // Safe velocity
        case _ =>
          state.speedLimit / 3.6 // Free-flow speed (km/h to m/s)
      }

      val safeTargetVel = if (targetVel.isNaN || targetVel.isInfinite) 0.0 else targetVel
      val velChange = (safeTargetVel - vehicle.velocity) * 0.5 * state.microTimeStep
      val rawNewVelocity = vehicle.velocity + velChange
      val newVelocity = math.max(0.0, math.min(if (rawNewVelocity.isNaN || rawNewVelocity.isInfinite) 0.0 else rawNewVelocity, state.speedLimit / 3.6))
      val newPosition = vehicle.position + newVelocity * state.microTimeStep
      val newAcceleration = velChange / state.microTimeStep

      if (newVelocity < 0.1) {
        vehicleWaitingSeconds.update(
          vehicle.actorId,
          vehicleWaitingSeconds.getOrElse(vehicle.actorId, 0.0) + state.microTimeStep
        )
      }

      // Update vehicle state in queue
      vehicles(i) = vehicle.copy(
        position = newPosition,
        velocity = newVelocity,
        acceleration = newAcceleration
      )

      val updateMicro = MicroUpdateData(
        subTick = subTick,
        position = newPosition,
        velocity = newVelocity,
        acceleration = newAcceleration,
        currentLane = laneId,
        leaderVehicle = leader.map(_.actorId),
        gapToLeader = gap,
        leaderVelocity = leaderVel,
        safeVelocity = newVelocity
      )

      /*
      * when vehicle position was more than link length, we need to sent leave link data to vehicle and remove it from lane queue.
      * */

      if (vehicle.position >= state.length || subTick >= state.microTicksPerGlobalTick - 1) {
        sendMessageTo(
          entityId = vehicle.actorId,
          shardId = vehicle.shardId,
          data = updateMicro,
          eventType = "MicroUpdate", // Custom event type
          actorType = LoadBalancedDistributed
        )
      }
    }

    // Keep queue ordered front -> back (highest position first)
    // so leader lookup by index (i - 1) stays physically consistent.
    val ordered = vehicles.sortBy(v => -v.position)
    vehicles.clear()
    vehicles ++= ordered
  }

  private def ensureSummaryTick(tick: Tick): Unit = {
    if (summaryTick != tick) {
      summaryTick = tick
      tickLoaded = 0
      tickInserted = 0
      tickArrived = 0
      tickTravelTimeSum = 0.0
      tickProcessingDurationMs = 0L
    }
  }

  private def onVehicleInserted(): Unit = {
    tickInserted += 1
    cumulativeLoaded += 1
  }

  private def onVehicleArrived(travelTime: Double): Unit = {
    tickArrived += 1
    cumulativeArrived += 1
    if (travelTime > 0) {
      tickTravelTimeSum += travelTime
    }
  }

  private def emitSumoSummaryStep(tick: Tick): Unit = {
    val running = state.totalVehicles
    val halting = if (state.isMicroMode) {
      state.vehiclesByLane.valuesIterator.map(_.count(_.velocity <= 0.1)).sum
    } else {
      0
    }
    val meanSpeed = if (state.isMicroMode) {
      val allVehicles = state.vehiclesByLane.valuesIterator.flatMap(_.iterator).toVector
      if (allVehicles.nonEmpty) allVehicles.map(_.velocity).sum / allVehicles.size else 0.0
    } else {
      state.currentSpeed
    }
    val meanSpeedRelative = if (state.freeSpeed > 0) meanSpeed / state.freeSpeed else 0.0
    val runningVehicleIds = state.registered.iterator.map(_.actorId).toVector
    val meanTravelTime =
      if (runningVehicleIds.nonEmpty)
        runningVehicleIds.map(id => math.max(0L, tick - vehicleEntryTick.getOrElse(id, tick)).toDouble).sum / runningVehicleIds.size
      else 0.0
    val meanWaitingTime =
      if (runningVehicleIds.nonEmpty)
        runningVehicleIds.map(id => vehicleWaitingSeconds.getOrElse(id, 0.0)).sum / runningVehicleIds.size
      else 0.0
    val waiting = math.max(0L, cumulativeLoadedVehicles - cumulativeLoaded).toInt

    report(
      data = Map(
        "event_type" -> "sumo_summary_step",
        "scope" -> "link",
        "link_id" -> getEntityId,
        "mode" -> state.simulationMode.toString,
        "time" -> tick,
        "loaded" -> cumulativeLoadedVehicles,
        "inserted" -> tickInserted,
        "running" -> running,
        "waiting" -> waiting,
        "ended" -> cumulativeArrived,
        "arrived" -> tickArrived,
        "collisions" -> 0,
        "teleports" -> 0,
        "halting" -> halting,
        "stopped" -> 0,
        "meanWaitingTime" -> meanWaitingTime,
        "meanTravelTime" -> meanTravelTime,
        "meanSpeed" -> meanSpeed,
        "meanSpeedRelative" -> meanSpeedRelative,
        "discarded" -> cumulativeDiscarded,
        "duration" -> tickProcessingDurationMs,
        "tick" -> tick
      ),
      label = "sumo_summary_step"
    )
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
        logDebug(s"Published dynamic cost to Kafka: weight=${dynamicCost.totalCost}, congestion=${dynamicCost.congestionFactor}, vehicles=${dynamicCost.vehicleCount}")
      case scala.util.Failure(e) =>
        logWarn(s"Failed to publish dynamic cost to Kafka: ${e.getMessage}")
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
