package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.mobility.actor.Movable
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum._
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.mobility.util.{CityMapUtil, GPSUtil, SpeedUtil}
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed

import org.interscity.htc.model.hybrid.entity.state.{HybridCarState, MicroCarState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}

/** HybridCar actor supporting both MESO and MICRO simulation modes.
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
class HybridCar(
  private val properties: Properties
) extends Movable[HybridCarState](
      properties = properties
    ) {
  
  /** Car-following model for microscopic simulation.
    */
  private val carFollowingModel: CarFollowingModel = KraussModel()
  
  /** Current link being traversed (for micro mode).
    */
  private var currentLinkId: Option[String] = None
  
  /** Tick when entered current link (for travel time calculation).
    */
  private var linkEntryTick: Option[Tick] = None
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
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
    try {
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
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
    finishJourney("onFinish_called", nodeId)
    onFinishSpontaneous()
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
}

/** HybridCar companion object.
  */
object HybridCar {
  def apply(properties: Properties): HybridCar = {
    new HybridCar(properties)
  }
}
