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
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent

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

  /** MESO exit tick — the tick at which link traversal completes.
    * Used to prevent stale Waiting-poll ticks from triggering premature requestSignalState.
    */
  private var mesoExitTick: Option[Tick] = None

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

  /** Maximum simulation end tick - vehicles must finish by this tick. */
  private lazy val simulationEndTick: Tick = model.hybrid.util.VehicleSimulationConfig.simulationEndTick

  /** Expected tick when red signal phase ends.
    * Prevents stale WaitingSignalState poll ticks from triggering premature leavingLink.
    */
  private var signalWaitUntilTick: Option[Tick] = None

  /** Counter for consecutive ticks in WaitingSignalState without Node response. */
  private var signalStateRetryCounter: Int = 0
  
  /** Maximum ticks to wait for signal state response before recovering. */
  private val MaxSignalStateRetries: Int = 100

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
    if (state == null) {
      logWarn("Bicycle state is null, cannot process spontaneous event — sending FinishEvent to unblock TimeManager")
      onFinishSpontaneous(None)
      return
    }

    // Check if vehicle is parked (passive state)
    if (state.status == Parked) {
      onFinishSpontaneous(None)
      return
    }
    
    // Safety net: Force-finish vehicles that exceed simulation end time (only when extension is disabled)
    if (!model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
        && currentTick >= simulationEndTick && state.status != Finished) {
      logInfo(s"Bicycle ${getEntityId} exceeded simulation end time ($simulationEndTick) at tick $currentTick, force-finishing.")
      val finalNode = Option(getCurrentNode).getOrElse(state.destination)
      finishJourney("simulation_time_exceeded", finalNode)
      onFinishPrivateVehicle(finalNode)
      onFinishSpontaneous(None)
      selfDestruct()
      return
    }
    
    state.status match {
      case Start =>
        requestRoute()
      
      case Ready =>
        enterLink()
      
      case WaitingSignal =>
        // Gate on signalWaitUntilTick to prevent stale poll ticks from
        // triggering premature leavingLink before the red signal phase ends.
        signalWaitUntilTick match {
          case Some(waitTick) if currentTick < waitTick =>
            onFinishSpontaneous(Some(waitTick))
          case _ =>
            signalWaitUntilTick = None
            leavingLink()
        }
      
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
          // MESO mode: only request signal state after travel time has elapsed
          mesoExitTick match {
            case Some(exitTick) if currentTick < exitTick =>
              // Travel time hasn't elapsed yet, wait
              onFinishSpontaneous(Some(exitTick))
            case _ =>
              mesoExitTick = None
              requestSignalState()
          }
        }
      
      case WaitingSignalState =>
        signalStateRetryCounter += 1
        if (signalStateRetryCounter > MaxSignalStateRetries) {
          logWarn(s"Bicycle ${getEntityId} stuck in WaitingSignalState for $signalStateRetryCounter ticks at tick $currentTick (Node not responding). Recovering by leaving link.")
          signalStateRetryCounter = 0
          leavingLink()
        } else {
          // Still waiting for Node response, reschedule
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
    val currentPathNode = state.movableCurrentPath.map(_._2).orNull
    val routeDepleted = state.movableBestRoute.forall(_.isEmpty)
    if (state.destination == currentPathNode || routeDepleted) {
      val currentNodeId = getCurrentNode
      if (currentNodeId != null) {
        finishJourney("reached_destination", currentNodeId)
        onFinishPrivateVehicle(currentNodeId)
      } else {
        finishJourney("no_current_node", "unknown")
        onFinishPrivateVehicle("unknown")
      }
      onFinishSpontaneous(None)
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
    signalStateRetryCounter = 0  // Reset stuck counter on signal response
    if (data.phase == Red) {
      state.status = WaitingSignal
      signalWaitUntilTick = Some(data.nextTick)
      val waitTicks = math.max(0L, data.nextTick - currentTick)
      if (waitTicks > 0) {
        updateHaltingState(speed = 0.0, deltaSeconds = waitTicks.toDouble)
      }
      // Report signal waiting event
      report(
        data = Map(
          "event_type" -> "signal_wait",
          "vehicle_type" -> "bicycle",
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
    mesoExitTick = None
    signalWaitUntilTick = None
    state.status = Ready
    super.leavingLink()
  }
  
  override def requestRoute(): Unit = {
    if (state.status == Finished) {
      return
    }
    val origin = getTripOrigin.getOrElse(state.origin)
    val destination = getTripDestination.getOrElse(state.destination)
    if (sumoDepartTick.nonEmpty) {
      sumoRerouteNo += 1
    }
    try {
      GPSUtil.calcRoute(originId = origin, destinationId = destination) match {
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
              "vehicle_id" -> getEntityId,
              "bicycle_id" -> getEntityId,
              "origin" -> origin,
              "destination" -> destination,
              "route_length" -> pathQueue.size,
              "tick" -> currentTick
            ),
            label = "journey_started"
          )
          
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            finishJourney("already_at_destination", origin)
          }
        
        case None =>
          logError(s"Failed to calculate route for bicycle ${getEntityId}")
          finishJourney("route_calculation_failed", origin)
      }
    } catch {
      case e: Exception =>
        logError(s"Exception during bicycle route request: ${e.getMessage}", e)
        finishJourney("exception", origin)
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
      sumoArrivalSpeed = data.velocity
      updateHaltingState(data.velocity, sumoCurrentMicroTimeStepSeconds)
      
      log.debug(s"Bicycle micro update: pos=${data.position}, vel=${data.velocity}")
    }
  }
  
  /** Handle leaving MICRO link.
    */
  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    logDebug(s"Bicycle leaving MICRO link ${data.linkId}")
    
    val travelTime = linkEntryTick.map(entryTick => currentTick - entryTick).getOrElse(0L)
    
    state.distance += data.distanceTraveled
    sumoArrivalSpeed = data.finalVelocity
    sumoArrivalLane = Some(s"${data.linkId}_${state.microState.map(_.currentLane).getOrElse(0)}")
    sumoArrivalPos = data.finalPosition
    updateHaltingState(data.finalVelocity, 0.0)
    
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
    sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, data.linkFreeSpeed)
    updateHaltingState(bicycleSpeed, 0.0)
    if (sumoDepartTick.isEmpty) {
      sumoDepartTick = Some(currentTick)
      sumoDepartSpeed = bicycleSpeed
      sumoDepartLane = Some(s"${event.actorRefId}_0")
      sumoDepartPos = 0.0
    }
    
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
    
    val exitTick = currentTick + Math.ceil(time).toLong
    mesoExitTick = Some(exitTick)
    onFinishSpontaneous(Some(exitTick))
  }
  
  /** Handle leaving MESO link.
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
    mesoExitTick = None
    
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

    val routeDepleted = state.movableCurrentPath.isEmpty && state.movableBestRoute.forall(_.isEmpty)
    if (routeDepleted && state.status != Finished) {
      finishJourney("reached_destination", state.destination)
      onFinishPrivateVehicle(state.destination)
      onFinishSpontaneous(None)
      selfDestruct()
    } else {
      onFinishSpontaneous(Some(currentTick + 1))
    }
  }
  
  /** Finish bicycle journey.
    */
  private def finishJourney(reason: String, finalNode: String): Unit = {
    val destination = getTripDestination.getOrElse(state.destination)
    val origin = getTripOrigin.getOrElse(state.origin)
    report(
      data = Map(
        "event_type" -> "journey_completed",
        "vehicle_id" -> getEntityId,
        "bicycle_id" -> getEntityId,
        "origin" -> origin,
        "destination" -> destination,
        "final_node" -> finalNode,
        "reached_destination" -> (destination == finalNode),
        "completion_reason" -> reason,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "journey_completed"
    )

    reportSumoTripInfo(reason = reason, finalNode = finalNode)
    
    state.status = Finished
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
        "vehicle_type" -> "bicycle",
        "vType" -> "bicycle",
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

  override def onDestruct(event: DestructEvent): Unit = {
    if (state != null && !sumoTripInfoReported && state.status != Finished) {
      val fallbackNode = Option(getCurrentNode)
        .orElse(state.movableCurrentPath.map(_._2))
        .getOrElse("unknown")
      finishJourney("actor_destructed_before_completion", fallbackNode)
      onFinishPrivateVehicle(fallbackNode)
    }
  }
}

/** Bicycle companion object.
  */
object Bicycle {
  def apply(properties: Properties): Bicycle = {
    new Bicycle(properties)
  }
}
