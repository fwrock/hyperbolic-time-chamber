package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.{EventTypeEnum, MovableStatusEnum, SimulationModeEnum}
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.*
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.{CityMapUtil, GPSUtil, SpeedUtil}
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.hybrid.entity.state.{CarState, DriverAttributes, MicroCarState}
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent

/** Car actor supporting both MESO and MICRO simulation modes.
  * 
  * NOW A PRIVATE VEHICLE ASSET (Person-Centric Model):
  * - Passive (Parked) by default
  * - Activated by Person via StartTrip message
  * - Configured with person's DriverAttributes
  * - Reports back with TripCompleted
  * 
  * Extends the base Car actor with microscopic simulation capabilities.
  * The simulation mode is determined by the link the car is traversing.
  * 
  * MESO mode:
  * - Standard mesoscopic behavior (aggregate speed calculation)
  * - Single-tick traversal
  * - Compatible with existing links
  * 
  * MICRO mode:
  * - Individual positioning and velocity tracking
  * - Car-following model (Krauss)
  * - Lane management
  * - Multi-tick traversal with sub-ticks
  * 
  * Mode transitions occur automatically when entering/exiting links.
  * 
  * @param properties Actor properties
  */
class Car(
  private val properties: Properties
) extends Movable[CarState](
      properties = properties
    ) with PrivateVehicle[CarState] {
  
  /** Car-following model for microscopic simulation.
    */
  private val carFollowingModel: CarFollowingModel = KraussModel()
  
  /** Current link being traversed.
    */
  private var currentLinkId: Option[String] = None
  
  /** Length of current link (cached from link info, no map lookup needed).
    */
  private var currentLinkLength: Double = 0.0
  
  /** Tick when entered current link (for travel time calculation).
    */
  private var linkEntryTick: Option[Tick] = None

  private var sumoDepartTick: Option[Tick] = None
  private var sumoDepartSpeed: Double = 0.0
  private var sumoArrivalSpeed: Double = 0.0
  private var sumoDepartLane: Option[String] = None
  private var sumoDepartPos: Double = 0.0
  private var sumoArrivalLane: Option[String] = None
  private var sumoArrivalPos: Double = 0.0
  private var sumoWaitingTimeSeconds: Double = 0.0
  private var sumoWaitingCount: Int = 0
  private var sumoStopTimeSeconds: Double = 0.0
  private var sumoIdealTravelTimeSeconds: Double = 0.0
  private var sumoCurrentMicroTimeStepSeconds: Double = 1.0
  private var sumoIsHalting: Boolean = false
  private var sumoRerouteNo: Int = 0
  private var sumoTripInfoReported: Boolean = false

  private def updateHaltingState(speed: Double, deltaSeconds: Double): Unit = {
    val isHaltingNow = speed < 0.1
    if (isHaltingNow) {
      if (!sumoIsHalting) {
        sumoWaitingCount += 1
      }
      sumoWaitingTimeSeconds += math.max(0.0, deltaSeconds)
    }
    sumoIsHalting = isHaltingNow
  }
  
  // ===== PrivateVehicle Accessor Methods =====
  
  override protected def getVehicleStatus: MovableStatusEnum = {
    if (state == null) Parked else state.status
  }
  override protected def setVehicleStatus(status: MovableStatusEnum): Unit = {
    if (state != null) state.movableStatus = status
  }
  override protected def getActorCurrentTick: Tick = currentTick
  override protected def getActorShardId: String = getShardId
  override protected def getActorEntityId: String = getEntityId
  override protected def scheduleNextTick(nextTick: Option[Tick]): Unit = onFinishSpontaneous(nextTick)
  override protected def getCurrentDistance: Double = if (state == null) 0.0 else state.distance
  override protected def sendVehicleMessage(entityId: String, shardId: String, data: AnyRef, eventType: String, actorType: CreationTypeEnum): Unit = {
    sendMessageTo(entityId = entityId, shardId = shardId, data = data, eventType = eventType, actorType = actorType)
  }
  override protected def logVehicleInfo(message: String): Unit = logInfo(message)
  override protected def logVehicleWarn(message: String): Unit = logWarn(message)
  override protected def logVehicleDebug(message: String): Unit = logDebug(message)
  
  // ===== End Accessor Methods =====
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null) {
      logWarn("Car state is null")
      onFinishSpontaneous(Some(currentTick + 1))
      return
    }

    // Check if vehicle is parked (passive state)
    if (state.movableStatus == Parked) {
      // Do nothing, wait for StartTrip from Person
      onFinishSpontaneous(None) // Unregister from TimeManager
      return
    }
    
    // DIAGNOSTIC: Log status every 1000 ticks to track progression
    if (currentTick % 1000 == 0) {
      logInfo(s"${getEntityId} actSpontaneous at tick $currentTick: status=${state.movableStatus}, movableStatus=${state.movableStatus}, destination=${state.destination}, routeSize=${state.movableBestRoute.map(_.size).getOrElse(0)}, isMicro=${state.isMicroMode}")
    }

    
    state.movableStatus match {
      case Moving =>
        if (state.isMicroMode) {
          // In MICRO mode, wait for updates from LinkMicroTimeManager
          // Check if reached end of link
          var shouldLeave = false
          state.microState.foreach { micro =>
            if (micro.positionInLink >= getCurrentLinkLength) {
              shouldLeave = true
            }
          }
          
          if (shouldLeave) {
            leavingLink()
          } else {
            // Continue in MICRO mode, schedule next check
            onFinishSpontaneous(Some(currentTick + 1))
          }
        } else {
          // MESO mode: standard behavior
          requestSignalState()
        }
      
      case WaitingSignal =>
        leavingLink()

      case WaitingSignalState =>
        onFinishSpontaneous(Some(currentTick + 1))
      
      case Stopped =>
        onFinishSpontaneous(Some(currentTick + 1))
      
      case _ =>
        super.actSpontaneous(event)
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    // Handle PrivateVehicle events first (StartTrip, ParkVehicle)
    if (handlePrivateVehicleEvent(event)) {
      return
    }
    
    event.data match {
      case d: SignalStateData => handleSignalState(event, d)
      case d: MicroEnterLinkData => handleMicroEnterLink(event, d)
      case d: MicroUpdateData => handleMicroUpdate(event, d)
      case d: MicroLeaveLinkData => handleMicroLeaveLink(event, d)
      case _ => super.actInteractWith(event)
    }
  }

  private def precomputedRoute = state.precomputedRoute
    .map { items =>
      items
        .flatMap { item =>
          if (item.linkId != null && item.linkId.nonEmpty && item.nodeId != null && item.nodeId.nonEmpty) {
            Some((item.linkId, item.nodeId))
          } else {
            None
          }
        }
    }
    .filter(_.nonEmpty)
    .map(items => scala.collection.mutable.Queue.from(items))
  
  override def requestRoute(): Unit = {
    if (state.movableStatus == Finished) {
      return
    }

    val precomputedPathQueue = precomputedRoute

    if (precomputedPathQueue.nonEmpty) {
      val fixedRoute = precomputedPathQueue.get
      state.bestRoute = Some(fixedRoute.clone())
      state.bestCost = fixedRoute.size.toDouble
      state.movableBestRoute = Some(fixedRoute)
      state.movableBestCost = state.bestCost
      state.movableStatus = Ready
      state.updateCurrentPath(None)

      report(
        data = Map(
          "event_type" -> "journey_started",
          "vehicle_id" -> getEntityId,
          "car_id" -> getEntityId,
          "origin" -> state.origin,
          "destination" -> state.destination,
          "route_cost" -> state.bestCost,
          "route_length" -> fixedRoute.size,
          "tick" -> currentTick,
          "route_source" -> "precomputed"
        ),
        label = "journey_started"
      )

      report(
        data = Map(
          "event_type" -> "route_planned",
          "car_id" -> getEntityId,
          "route_links" -> fixedRoute.map(_._1).mkString(","),
          "route_nodes" -> fixedRoute.map(_._2).mkString(","),
          "tick" -> currentTick,
          "route_source" -> "precomputed"
        ),
        label = "route_planned"
      )

      if (fixedRoute.nonEmpty) {
        enterLink()
      } else {
        finishJourney("already_at_destination", state.origin)
        onFinishSpontaneous(None)
      }
      return
    }

    if (state.bestRoute.nonEmpty) {
      val fixedRoute = state.bestRoute.get
      state.bestCost = fixedRoute.size.toDouble
      state.movableBestRoute = Some(fixedRoute)
      state.movableBestCost = state.bestCost
      state.movableStatus = Ready
      state.updateCurrentPath(None)

      report(
        data = Map(
          "event_type" -> "journey_started",
          "vehicle_id" -> getEntityId,
          "car_id" -> getEntityId,
          "origin" -> state.origin,
          "destination" -> state.destination,
          "route_cost" -> state.bestCost,
          "route_length" -> fixedRoute.size,
          "tick" -> currentTick,
          "route_source" -> "preloaded"
        ),
        label = "journey_started"
      )

      report(
        data = Map(
          "event_type" -> "route_planned",
          "car_id" -> getEntityId,
          "route_links" -> fixedRoute.map(_._1).mkString(","),
          "route_nodes" -> fixedRoute.map(_._2).mkString(","),
          "tick" -> currentTick,
          "route_source" -> "preloaded"
        ),
        label = "route_planned"
      )

      if (fixedRoute.nonEmpty) {
        enterLink()
      } else {
        finishJourney("already_at_destination", state.origin)
        onFinishSpontaneous(None)
      }
      return
    }
    
    if (sumoDepartTick.nonEmpty) {
      sumoRerouteNo += 1
    }

    logInfo(s"Car requestRoute: getTripOrigin=${getTripOrigin}, state.origin=${state.origin}, getTripDestination=${getTripDestination}, state.destination=${state.destination}")
    
    // Use trip origin/destination if set (from PrivateVehicle), otherwise use state
    val origin = getTripOrigin.getOrElse(state.origin)
    val destination = getTripDestination.getOrElse(state.destination)
    
    logInfo(s"Car requestRoute: resolved origin=${origin}, destination=${destination}")
    
    if (origin == null || destination == null) {
      logWarn(s"Car ${getEntityId} has null origin or destination: origin=$origin, destination=$destination")
      return
    }
    
    try {
      GPSUtil.calcRoute(originId = origin, destinationId = destination) match {
        case Some((cost, pathQueue)) =>
          state.bestCost = cost
          state.bestRoute = Some(pathQueue)
          state.movableBestRoute = Some(pathQueue)
          state.movableBestCost = cost
          state.movableStatus = Ready
          state.updateCurrentPath(None)
          
          // Report journey started
          report(
            data = Map(
              "event_type" -> "journey_started",
              "vehicle_id" -> getEntityId,
              "car_id" -> getEntityId,
              "origin" -> state.origin,
              "destination" -> state.destination,
              "route_cost" -> cost,
              "route_length" -> pathQueue.size,
              "tick" -> currentTick
            ),
            label = "journey_started"
          )
          
          // Report route planned
          report(
            data = Map(
              "event_type" -> "route_planned",
              "car_id" -> getEntityId,
              "route_links" -> pathQueue.map(_._1).mkString(","),
              "route_nodes" -> pathQueue.map(_._2).mkString(","),
              "tick" -> currentTick
            ),
            label = "route_planned"
          )
          
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            finishJourney("already_at_destination", state.origin)
          }
        
        case None =>
          logError(s"Failed to calculate route from ${state.origin} to ${state.destination}")
          finishJourney("route_calculation_failed", state.origin)
          onFinishSpontaneous(None)
      }
    } catch {
      case e: Exception =>
        logError(s"Exception during route request: ${e.getMessage}", e)
        finishJourney("exception_during_route_request", state.origin)
        onFinishSpontaneous(None)
    }
  }
  
  private def requestSignalState(): Unit = {
    val currentPathNode = state.movableCurrentPath.map(_._2).orNull
    val routeDepleted = state.movableBestRoute.forall(_.isEmpty)
    
    // DEBUG: Log destination check
    if (currentTick % 1000 == 0 || state.destination == currentPathNode || routeDepleted) {
      logInfo(s"${getEntityId} requestSignalState: destination=${state.destination}, currentPathNode=$currentPathNode, routeDepleted=$routeDepleted, tick=$currentTick")
    }
    
    if (state.destination == currentPathNode || routeDepleted) {
      val currentNodeId = getCurrentNode
      if (currentNodeId != null) {
        finishJourney("reached_destination", currentNodeId)
      } else {
        finishJourney("no_current_node", "unknown")
      }
      onFinishSpontaneous(None)
    } else {
      state.movableStatus = WaitingSignalState
      getCurrentNode match {
        case nodeId =>
          CityMapUtil.nodesById.get(nodeId) match {
            case Some(node) =>
              getNextLink match {
                case linkId =>
                  sendMessageTo(
                    entityId = node.id,
                    shardId = node.classType,
                    RequestSignalStateData(targetLinkId = linkId),
                    EventTypeEnum.RequestSignalState.toString
                  )
                  // Schedule to wait for signal response
                  onFinishSpontaneous(Some(currentTick + 1))
                case null =>
                  // No next link, finish
                  onFinishSpontaneous(None)
              }
            case None =>
              // Node not found, retry next tick
              onFinishSpontaneous(Some(currentTick + 1))
          }
        case null =>
          // No current node, retry next tick
          onFinishSpontaneous(Some(currentTick + 1))
      }
    }
  }
  
  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
    if (data.phase == Red) {
      state.movableStatus = WaitingSignal
      val waitTicks = math.max(0L, data.nextTick - currentTick)
      if (waitTicks > 0) {
        updateHaltingState(speed = 0.0, deltaSeconds = waitTicks.toDouble)
      }
      
      // Report signal wait event
      report(
        data = Map(
          "event_type" -> "signal_wait",
          "vehicle_type" -> "car",
          "vehicle_id" -> getEntityId,
          "phase" -> data.phase.toString,
          "wait_until_tick" -> data.nextTick,
          "tick" -> currentTick
        ),
        label = "signal_wait"
      )
      
      onFinishSpontaneous(Some(data.nextTick))
    } else {
      leavingLink()
    }
  }
  
  override def leavingLink(): Unit = {
    state.movableStatus = Ready
    super.leavingLink()
  }
  
  override protected def onFinish(nodeId: String): Unit = {
    // Use PrivateVehicle trip completion (reports to Person)
    onFinishPrivateVehicle(nodeId)
    
    // Also report journey statistics for analytics
    finishJourney("onFinish_called", nodeId)
  }
  
  /** Handle entering MICRO link.
    */
  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit = {
    logDebug(s"Entering MICRO link ${data.linkId}, assigned to lane ${data.assignedLane}")
    
    // Cache link information (no map lookup needed)
    currentLinkId = Some(data.linkId)
    currentLinkLength = data.linkLength
    linkEntryTick = Some(currentTick)
    
    // Initialize microscopic state
    val initialMicroState = MicroCarState(
      positionInLink = 0.0,
      velocity = state.microState.map(_.velocity).getOrElse(data.speedLimit * 0.8), // Start at 80% speed limit
      acceleration = 0.0,
      currentLane = data.assignedLane,
      leaderVehicle = None,
      gapToLeader = data.linkLength, // Initially, full link ahead
      leaderVelocity = data.speedLimit,
      desiredVelocity = data.speedLimit
    )
    
    // Activate MICRO mode
    state.activateMicroMode(initialMicroState)
    state.movableStatus = Moving
    sumoCurrentMicroTimeStepSeconds = math.max(0.001, data.microTimeStep)
    sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, data.speedLimit)
    updateHaltingState(initialMicroState.velocity, 0.0)
    if (sumoDepartTick.isEmpty) {
      sumoDepartTick = Some(currentTick)
      sumoDepartSpeed = initialMicroState.velocity
      sumoDepartLane = Some(s"${data.linkId}_${initialMicroState.currentLane}")
      sumoDepartPos = 0.0
    }
    
    // Report micro enter
    report(
      data = Map(
        "event_type" -> "enter_micro_link",
        "car_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "lane" -> data.assignedLane,
        "link_length" -> data.linkLength,
        "speed_limit" -> data.speedLimit,
        "initial_velocity" -> initialMicroState.velocity,
        "micro_time_step" -> data.microTimeStep,
        "ticks_per_global_tick" -> data.ticksPerGlobalTick,
        "tick" -> currentTick
      ),
      label = "enter_micro_link"
    )
    
    // Schedule next spontaneous event (LinkMicroTimeManager will send updates)
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Handle microscopic update from LinkMicroTimeManager.
    */
  private def handleMicroUpdate(event: ActorInteractionEvent, data: MicroUpdateData): Unit = {
    state.microState.foreach { micro =>
      // Update microscopic state
      val updatedMicro = micro.copy(
        positionInLink = data.position,
        velocity = data.velocity,
        acceleration = data.acceleration,
        currentLane = data.currentLane,
        leaderVehicle = data.leaderVehicle,
        gapToLeader = data.gapToLeader,
        leaderVelocity = data.leaderVelocity
      )
      
      state.updateMicroState(updatedMicro)
      sumoArrivalSpeed = data.velocity
      updateHaltingState(data.velocity, sumoCurrentMicroTimeStepSeconds)
      
      // Log detailed update (trace level to avoid spam)
      if (currentTick % 1000 == 0) {
        logDebug(s"Micro update tick $currentTick and sub-tick ${data.subTick}: pos=${data.position}, vel=${data.velocity}, accel=${data.acceleration}")
      }
      // Check if reached end of link
      if (data.position >= getCurrentLinkLength) {
        logDebug(s"Reached end of MICRO link at position ${data.position}")
        // Will trigger leavingLink on next spontaneous event
      }
    }
  }
  
  /** Handle leaving MICRO link.
    */
  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    logDebug(s"Leaving MICRO link ${data.linkId}")
    
    // Calculate actual travel time
    val travelTime = linkEntryTick.map(entryTick => currentTick - entryTick).getOrElse(0L)
    
    // Update distance
    state.distance += data.distanceTraveled
    sumoArrivalSpeed = data.finalVelocity
    sumoArrivalLane = Some(s"${data.linkId}_${state.microState.map(_.currentLane).getOrElse(0)}")
    sumoArrivalPos = data.finalPosition
    updateHaltingState(data.finalVelocity, 0.0)
    
    // Report micro leave
    report(
      data = Map(
        "event_type" -> "leave_micro_link",
        "car_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "final_position" -> data.finalPosition,
        "final_velocity" -> data.finalVelocity,
        "travel_time_ticks" -> travelTime,
        "travel_time_seconds" -> data.travelTime,
        "distance_traveled" -> data.distanceTraveled,
        "average_speed" -> data.averageSpeed,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_micro_link"
    )
    
    // Deactivate MICRO mode and clear cached link data
    state.deactivateMicroMode()
    currentLinkId = None
    currentLinkLength = 0.0
    linkEntryTick = None
    
    // Schedule next action
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Handle entering MESO link (standard behavior).
    */
  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    logDebug(s"Entering MESO link ${event.actorRefId}")
    
    // Cache link information (no map lookup needed)
    currentLinkId = Some(event.actorRefId)
    currentLinkLength = data.linkLength
    linkEntryTick = Some(currentTick)
    
    val speed = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )
    
    val time = data.linkLength / speed
    state.movableStatus = Moving
    sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, data.linkFreeSpeed)
    updateHaltingState(speed, 0.0)
    if (sumoDepartTick.isEmpty) {
      sumoDepartTick = Some(currentTick)
      sumoDepartSpeed = speed
      sumoDepartLane = Some(s"${event.actorRefId}_0")
      sumoDepartPos = 0.0
    }
    
    // Report meso enter
    report(
      data = Map(
        "event_type" -> "enter_link",
        "car_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "link_length" -> data.linkLength,
        "link_capacity" -> data.linkCapacity,
        "cars_in_link" -> data.linkNumberOfCars,
        "free_speed" -> data.linkFreeSpeed,
        "calculated_speed" -> speed,
        "travel_time" -> time,
        "lanes" -> data.linkLanes,
        "tick" -> currentTick
      ),
      label = "enter_link"
    )
    
    if (time.isNaN || time.isInfinite || time < 0) {
      logError(s"Invalid time calculated: $time for link ${data.linkLength}m at speed $speed")
    }
    
    onFinishSpontaneous(Some(currentTick + Math.ceil(time).toLong))
  }
  
  /** Handle leaving MESO link (standard behavior).
    */
  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
    sumoArrivalSpeed = 0.0
    sumoArrivalLane = Some(s"${event.actorRefId}_0")
    sumoArrivalPos = data.linkLength
    updateHaltingState(0.0, 0.0)
    
    // Clear cached link data
    currentLinkId = None
    currentLinkLength = 0.0
    linkEntryTick = None
    
    // Report meso leave
    report(
      data = Map(
        "event_type" -> "leave_link",
        "car_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "link_length" -> data.linkLength,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_link"
    )

    val routeDepleted = state.movableCurrentPath.isEmpty && state.movableBestRoute.forall(_.isEmpty)
    
    // DEBUG: Log leavingLink check
    if (currentTick % 1000 == 0 || routeDepleted) {
      logInfo(s"${getEntityId} leavingLink: routeDepleted=$routeDepleted, currentPath=${state.movableCurrentPath}, bestRoute size=${state.movableBestRoute.map(_.size).getOrElse(0)}, status=${state.status}, tick=$currentTick")
    }
    
    if (routeDepleted && state.status != Finished) {
      state.movableStatus = Finished
      finishJourney("reached_destination", state.destination)
      onFinishPrivateVehicle(state.destination)
      selfDestruct()
      onFinishSpontaneous(None)
    } else {
      onFinishSpontaneous(Some(currentTick + 1))
    }
  }
  
  /** Finish journey and report statistics.
    */
  private def finishJourney(reason: String, finalNode: String): Unit = {
    val destination = getTripDestination.getOrElse(state.destination)
    val origin = getTripOrigin.getOrElse(state.origin)
    logInfo(s"Finishing journey for Car ${getEntityId}: reason=$reason, origin=$origin, destination=$destination, finalNode=$finalNode, totalDistance=${state.distance}, bestCost=${state.bestCost}, tick=$currentTick")
    report(
      data = Map(
        "event_type" -> "journey_completed",
        "vehicle_id" -> getEntityId,
        "car_id" -> getEntityId,
        "origin" -> origin,
        "destination" -> destination,
        "final_node" -> finalNode,
        "reached_destination" -> (destination == finalNode),
        "completion_reason" -> reason,
        "total_distance" -> state.distance,
        "best_cost" -> state.bestCost,
        "tick" -> currentTick
      ),
      label = "journey_completed"
    )
    
    report(
      data = Map(
        "event_type" -> "vehicle_event_count",
        "car_id" -> getEntityId,
        "tick" -> currentTick
      ),
      label = "vehicle_event_count"
    )

    reportSumoTripInfo(reason = reason, finalNode = finalNode)

    state.movableStatus = Finished
  }

  private def reportSumoTripInfo(reason: String, finalNode: String): Unit = {
    if (sumoTripInfoReported) return

    val destination = getTripDestination.getOrElse(state.destination)
    val origin = getTripOrigin.getOrElse(state.origin)
    val plannedDepart = getTripStartTick.getOrElse(state.startTick)
    val depart = sumoDepartTick.getOrElse(plannedDepart)
    val arrival = currentTick
    val duration = math.max(0L, arrival - depart)
    val routeLength = state.distance
    val expectedTravelTime = math.max(0.0, sumoIdealTravelTimeSeconds)
    val timeLoss = math.max(0.0, duration.toDouble - expectedTravelTime)
    val vaporized = reason == "actor_destructed_before_completion"
    val departDelay = math.max(0L, depart - plannedDepart)

    report(
      data = Map(
        "event_type" -> "sumo_tripinfo",
        "vehicle_id" -> getEntityId,
        "vehicle_type" -> "car",
        "vType" -> "car",
        "origin" -> origin,
        "destination" -> destination,
        "final_node" -> finalNode,
        "completion_reason" -> reason,
        "depart" -> depart,
        "arrival" -> arrival,
        "departLane" -> sumoDepartLane.getOrElse(""),
        "departPos" -> sumoDepartPos,
        "arrivalLane" -> sumoArrivalLane.getOrElse(""),
        "arrivalPos" -> sumoArrivalPos,
        "duration" -> duration,
        "routeLength" -> routeLength,
        "waitingTime" -> sumoWaitingTimeSeconds,
        "waitingCount" -> sumoWaitingCount,
        "stopTime" -> sumoStopTimeSeconds,
        "timeLoss" -> timeLoss,
        "departDelay" -> departDelay,
        "rerouteNo" -> sumoRerouteNo,
        "arrivalSpeed" -> sumoArrivalSpeed,
        "departSpeed" -> sumoDepartSpeed,
        "speedFactor" -> getDriverAttributes.maxSpeedFactor,
        "vaporized" -> vaporized,
        "tick" -> currentTick
      ),
      label = "sumo_tripinfo"
    )

    sumoTripInfoReported = true
  }
  
  /** Get current link length (cached from link entry, no map lookup).
    */
  private def getCurrentLinkLength: Double = {
    if (currentLinkLength > 0.0) {
      currentLinkLength
    } else {
      logWarn(s"Current link length not cached for Car ${getEntityId}, using default")
      1000.0 // Default fallback
    }
  }
  
  // ========== PrivateVehicle abstract method implementations ==========
  
  /** Apply driver attributes to car physics (override from PrivateVehicle).
    */
  override protected def applyDriverAttributes(attrs: DriverAttributes): Unit = {
    super.applyDriverAttributes(attrs)
    
    // Apply to micro state if exists
    state.microState.foreach { micro =>
      val updatedMicro = micro.copy(
        desiredVelocity = micro.desiredVelocity * attrs.maxSpeedFactor,
        reactionTime = attrs.reactionTime,
        minGap = micro.minGap * attrs.minGapFactor,
        // Aggressiveness affects acceleration (more aggressive = higher accel)
        maxAcceleration = micro.maxAcceleration * (0.8 + 0.4 * attrs.aggressiveness)
      )
      state.updateMicroState(updatedMicro)
    }
    
    logInfo(s"Car ${getEntityId} configured with driver attributes: " +
      s"aggressiveness=${attrs.aggressiveness}, maxSpeedFactor=${attrs.maxSpeedFactor}")
  }

  override def onDestruct(event: DestructEvent): Unit = {
    if (state == null) {
      logDebug(s"${getEntityId}: onDestruct called with null state")
      return
    }

    val fallbackNode = Option(getCurrentNode)
      .orElse(state.movableCurrentPath.map(_._2))
      .getOrElse("unknown")
    if (state.status != Finished && fallbackNode != "unknown") {
      finishJourney("actor_destructed_before_completion", fallbackNode)
    }
  }
}

/** Car companion object.
  */
object Car {
  def apply(properties: Properties): Car = {
    new Car(properties)
  }
}
