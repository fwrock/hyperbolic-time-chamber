package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.hybrid.actor.Movable
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum._
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.{CityMapUtil, GPSUtil, SpeedUtil}
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed

import org.interscity.htc.model.hybrid.entity.state.{CarState, MicroCarState, DriverAttributes}
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.model.hybrid.entity.event.data.person._
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}
import org.interscity.htc.core.enumeration.CreationTypeEnum

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
  
  /** Current link being traversed (for micro mode).
    */
  private var currentLinkId: Option[String] = None
  
  /** Tick when entered current link (for travel time calculation).
    */
  private var linkEntryTick: Option[Tick] = None
  
  // ===== PrivateVehicle Accessor Methods =====
  
  override protected def getVehicleStatus: org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum = state.status
  override protected def setVehicleStatus(status: org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum): Unit = {
    state.status = status
  }
  override protected def getActorCurrentTick: Tick = currentTick
  override protected def getActorShardId: String = getShardId
  override protected def getActorEntityId: String = getEntityId
  override protected def scheduleNextTick(nextTick: Option[Tick]): Unit = onFinishSpontaneous(nextTick)
  override protected def getCurrentDistance: Double = state.distance
  override protected def sendVehicleMessage(entityId: String, shardId: String, data: AnyRef, eventType: String, actorType: CreationTypeEnum): Unit = {
    sendMessageTo(entityId = entityId, shardId = shardId, data = data, eventType = eventType, actorType = actorType)
  }
  override protected def logVehicleInfo(message: String): Unit = logInfo(message)
  override protected def logVehicleWarn(message: String): Unit = logWarn(message)
  override protected def logVehicleDebug(message: String): Unit = logDebug(message)
  
  // ===== End Accessor Methods =====
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    // Check if vehicle is parked (passive state)
    if (state.status == Parked) {
      // Do nothing, wait for StartTrip from Person
      onFinishSpontaneous(None) // Unregister from TimeManager
      return
    }
    
    state.status match {
      case Moving =>
        if (state.isMicroMode) {
          // In MICRO mode, wait for updates from LinkMicroTimeManager
          // Check if reached end of link
          state.microState.foreach { micro =>
            if (micro.positionInLink >= getCurrentLinkLength) {
              leavingLink()
            }
          }
        } else {
          // MESO mode: standard behavior
          requestSignalState()
        }
      
      case WaitingSignal =>
        leavingLink()
      
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
  
  override def requestRoute(): Unit = {
    if (state.status == Finished) {
      return
    }
    
    // Use trip origin/destination if set (from PrivateVehicle), otherwise use state
    val origin = getTripOrigin.getOrElse(state.origin)
    val destination = getTripDestination.getOrElse(state.destination)
    
    if (origin == null || destination == null) {
      logWarn(s"Car ${getEntityId} has null origin or destination")
      return
    }
    
    try {
      GPSUtil.calcRoute(originId = origin, destinationId = destination) match {
        case Some((cost, pathQueue)) =>
          state.bestCost = cost
          state.bestRoute = Some(pathQueue)
          state.status = Ready
          state.updateCurrentPath(None)
          
          // Report journey started
          report(
            data = Map(
              "event_type" -> "journey_started",
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
      }
    } catch {
      case e: Exception =>
        logError(s"Exception during route request: ${e.getMessage}", e)
        finishJourney("exception_during_route_request", state.origin)
    }
  }
  
  private def requestSignalState(): Unit = {
    if (state.destination == state.currentPath.map(_._2).orNull || state.bestRoute.isEmpty) {
      val currentNodeId = getCurrentNode
      if (currentNodeId != null) {
        finishJourney("reached_destination", currentNodeId)
      } else {
        finishJourney("no_current_node", "unknown")
      }
      selfDestruct()
    } else {
      state.status = WaitingSignalState
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
                case null =>
              }
            case None =>
          }
        case null =>
      }
    }
  }
  
  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
    if (data.phase == Red) {
      state.status = WaitingSignal
      onFinishSpontaneous(Some(data.nextTick))
    } else {
      leavingLink()
    }
  }
  
  override def leavingLink(): Unit = {
    state.status = Ready
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
    
    currentLinkId = Some(data.linkId)
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
    state.status = Moving
    
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
      
      // Log detailed update (trace level to avoid spam)
      log.debug(s"Micro update sub-tick ${data.subTick}: pos=${data.position}, vel=${data.velocity}, accel=${data.acceleration}")
      
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
    
    // Deactivate MICRO mode
    state.deactivateMicroMode()
    currentLinkId = None
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
    
    val speed = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )
    
    val time = data.linkLength / speed
    state.status = Moving
    
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
    
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Finish journey and report statistics.
    */
  private def finishJourney(reason: String, finalNode: String): Unit = {
    report(
      data = Map(
        "event_type" -> "journey_completed",
        "car_id" -> getEntityId,
        "origin" -> state.origin,
        "destination" -> state.destination,
        "final_node" -> finalNode,
        "reached_destination" -> (state.destination == finalNode),
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
    
    state.status = Finished
  }
  
  /** Get current link length (for micro mode position checking).
    */
  private def getCurrentLinkLength: Double = {
    currentLinkId.flatMap { linkId =>
      CityMapUtil.edgeLabelsById.get(linkId).map(_.length)
    }.getOrElse(1000.0) // Default fallback
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
}

/** Car companion object.
  */
object Car {
  def apply(properties: Properties): Car = {
    new Car(properties)
  }
}
