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
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.*
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.{CityMapUtil, GPSUtil, SpeedUtil}
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.hybrid.entity.state.{BicycleState, MicroBicycleState, DriverAttributes}
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.model.hybrid.entity.event.data.person._
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}
import org.interscity.htc.core.enumeration.CreationTypeEnum

/** Bicycle actor - NEW vehicle type for hybrid simulator.
  * 
  * NOW A PRIVATE VEHICLE ASSET (Person-Centric Model):
  * - Passive (Parked) by default
  * - Activated by Person via StartTrip message
  * - Configured with person's DriverAttributes
  * - Reports back with TripCompleted
  * - Low speeds (typically 15-25 km/h)
  * - Prefer bike lanes when available
  * - Can share lanes with cars if necessary
  * - Lower acceleration and deceleration
  * - Smaller vehicle size (2m)
  * 
  * MESO mode:
  * - Aggregate bicycle flow
  * - Simplified speed calculations
  * 
  * MICRO mode:
  * - Detailed positioning with bicycle-specific parameters
  * - Bike lane preference
  * - Safety gap considerations (vulnerable user)
  * - Interactions with cars and other vehicles
  * 
  * @param properties Actor properties
  */
class Bicycle(
  private val properties: Properties
) extends Movable[BicycleState](
      properties = properties
    ) with PrivateVehicle[BicycleState] {
  
  /** Car-following model with bicycle parameters.
    */
  private val carFollowingModel: CarFollowingModel = KraussModel.withRandomness(0.3) // Higher randomness
  
  /** Current link being traversed.
    */
  private var currentLinkId: Option[String] = None
  
  /** Link entry tick.
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
      onFinishSpontaneous(None)
      return
    }
    
    state.status match {
      case Start =>
        requestRoute()
      
      case Ready =>
        enterLink()
      
      case WaitingSignal =>
        leavingLink()
      
      case Moving =>
        if (state.isMicroMode) {
          // MICRO mode: check position
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
          // MESO mode: simple progression
          onFinishSpontaneous(Some(currentTick + 1))
        }
      
      case Finished =>
        onFinishSpontaneous()
      
      case _ =>
        logWarn(s"Bicycle status not handled: ${state.status}")
        onFinishSpontaneous(Some(currentTick + 1))
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
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
  
  /** Request signal state from node before leaving link.
    */
  private def requestSignalState(): Unit = {
    if (state.destination == state.movableCurrentPath.map(_._2).orNull || state.bestRoute.isEmpty) {
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
        case nodeId if nodeId != null =>
          CityMapUtil.nodesById.get(nodeId) match {
            case Some(node) =>
              getNextLink match {
                case linkId if linkId != null =>
                  sendMessageTo(
                    entityId = node.id,
                    shardId = node.classType,
                    RequestSignalStateData(targetLinkId = linkId),
                    EventTypeEnum.RequestSignalState.toString
                  )
                  // Schedule to wait for signal response
                  onFinishSpontaneous(Some(currentTick + 1))
                case null =>
                  logWarn("No next link available")
                  leavingLink()
              }
            case None =>
              logWarn(s"Node $nodeId not found")
              leavingLink()
          }
        case null =>
          logWarn("No current node")
          leavingLink()
      }
    }
  }
  
  /** Handle signal state response from node.
    */
  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
    if (data.phase == Red) {
      state.status = WaitingSignal
      onFinishSpontaneous(Some(data.nextTick))
    } else {
      leavingLink()
    }
  }
  
  override def requestRoute(): Unit = {
    if (state.status == Finished) {
      return
    }
    try {
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          state.bestRoute = Some(pathQueue)
          // CRITICAL: Also update parent MovableState field!
          state.movableBestRoute = Some(pathQueue)
          state.movableBestCost = cost
          state.status = Ready
          state.movableStatus = Ready
          state.updateCurrentPath(None)
          
          // Report journey started
          report(
            data = Map(
              "event_type" -> "journey_started",
              "bicycle_id" -> getEntityId,
              "origin" -> state.origin,
              "destination" -> state.destination,
              "route_length" -> pathQueue.size,
              "tick" -> currentTick
            ),
            label = "journey_started"
          )
          
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            finishJourney("already_at_destination", state.origin)
          }
        
        case None =>
          logError(s"Failed to calculate route for bicycle ${getEntityId}")
          finishJourney("route_calculation_failed", state.origin)
      }
    } catch {
      case e: Exception =>
        logError(s"Exception during bicycle route request: ${e.getMessage}", e)
        finishJourney("exception", state.origin)
    }
  }
  
  /** Handle entering MICRO link.
    */
  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit = {
    logDebug(s"Bicycle entering MICRO link ${data.linkId}, lane ${data.assignedLane}")
    
    currentLinkId = Some(data.linkId)
    linkEntryTick = Some(currentTick)
    
    // Initialize microscopic state with bicycle parameters
    val initialMicroState = MicroBicycleState(
      positionInLink = 0.0,
      velocity = 5.0, // Start at ~18 km/h
      acceleration = 0.0,
      currentLane = findBikeLane(data).getOrElse(data.assignedLane), // Prefer bike lane
      leaderVehicle = None,
      gapToLeader = data.linkLength,
      leaderVelocity = 5.56, // 20 km/h
      maxAcceleration = 1.0, // Bicycle-specific (low)
      maxDeceleration = 3.0,
      minGap = 1.5, // Smaller gap
      desiredVelocity = 5.56, // 20 km/h typical bicycle speed
      reactionTime = 1.2,
      vehicleLength = 2.0, // Bicycle length
      prefersBikeLane = true,
      canUseSidewalk = false, // Configuration-dependent
      desiredLane = findBikeLane(data),
      laneChangeProgress = 0.0
    )
    
    // Activate MICRO mode
    state.activateMicroMode(initialMicroState)
    state.status = Moving
    
    // Report micro enter
    report(
      data = Map(
        "event_type" -> "enter_micro_link",
        "bicycle_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "lane" -> initialMicroState.currentLane,
        "prefers_bike_lane" -> initialMicroState.prefersBikeLane,
        "link_length" -> data.linkLength,
        "initial_velocity" -> initialMicroState.velocity,
        "tick" -> currentTick
      ),
      label = "enter_micro_link"
    )
    
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Handle microscopic update.
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
      
      log.debug(s"Bicycle micro update: pos=${data.position}, vel=${data.velocity}")
    }
  }
  
  /** Handle leaving MICRO link.
    */
  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    logDebug(s"Bicycle leaving MICRO link ${data.linkId}")
    
    val travelTime = linkEntryTick.map(entryTick => currentTick - entryTick).getOrElse(0L)
    
    state.distance += data.distanceTraveled
    
    // Report micro leave
    report(
      data = Map(
        "event_type" -> "leave_micro_link",
        "bicycle_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "travel_time_ticks" -> travelTime,
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
    
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Handle entering MESO link.
    */
  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    logDebug(s"Bicycle entering MESO link ${event.actorRefId}")
    
    // Simplified bicycle speed calculation (lower speed than cars)
    val bicycleSpeed = 5.56 // 20 km/h constant for MESO mode
    val time = data.linkLength / bicycleSpeed
    
    state.status = Moving
    
    // Report meso enter
    report(
      data = Map(
        "event_type" -> "enter_link",
        "bicycle_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "link_length" -> data.linkLength,
        "travel_time" -> time,
        "speed" -> bicycleSpeed,
        "tick" -> currentTick
      ),
      label = "enter_link"
    )
    
    onFinishSpontaneous(Some(currentTick + Math.ceil(time).toLong))
  }
  
  /** Handle leaving MESO link.
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
        "bicycle_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_link"
    )
    
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Finish bicycle journey.
    */
  private def finishJourney(reason: String, finalNode: String): Unit = {
    report(
      data = Map(
        "event_type" -> "journey_completed",
        "bicycle_id" -> getEntityId,
        "origin" -> state.origin,
        "destination" -> state.destination,
        "final_node" -> finalNode,
        "reached_destination" -> (state.destination == finalNode),
        "completion_reason" -> reason,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "journey_completed"
    )
    
    state.status = Finished
  }
  
  /** Find bike lane in link configuration (if any).
    */
  private def findBikeLane(data: MicroEnterLinkData): Option[Int] = {
    // Would query link configuration for bike lane
    // Simplified: assume lane 0 is bike lane if link has 3+ lanes
    if (data.numberOfLanes >= 3) Some(0) else None
  }
  
  /** Get current link length.
    */
  private def getCurrentLinkLength: Double = {
    currentLinkId.flatMap { linkId =>
      org.interscity.htc.model.hybrid.util.CityMapUtil.edgeLabelsById.get(linkId).map(_.length)
    }.getOrElse(500.0)
  }
  
  // ========== PrivateVehicle abstract method implementations ==========
  
  /** Apply driver attributes to bicycle physics.
    */
  override protected def applyDriverAttributes(attrs: DriverAttributes): Unit = {
    super.applyDriverAttributes(attrs)
    
    state.microState.foreach { micro =>
      val updatedMicro = micro.copy(
        desiredVelocity = micro.desiredVelocity * attrs.maxSpeedFactor,
        reactionTime = attrs.reactionTime,
        minGap = micro.minGap * attrs.minGapFactor
      )
      state.updateMicroState(updatedMicro)
    }
    
    logInfo(s"Bicycle ${getEntityId} configured with driver attributes")
  }
  
  /** Override onFinish to use PrivateVehicle completion.
    */
  override protected def onFinish(nodeId: String): Unit = {
    onFinishPrivateVehicle(nodeId)
    finishJourney("onFinish_called", nodeId)
  }
}

/** Bicycle companion object.
  */
object Bicycle {
  def apply(properties: Properties): Bicycle = {
    new Bicycle(properties)
  }
}
