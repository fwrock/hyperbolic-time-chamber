package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.hybrid.actor.Movable
import org.interscity.htc.model.hybrid.entity.event.data.bus.{BusLoadPassengerData, BusRequestPassengerData, BusRequestUnloadPassengerData, BusUnloadPassengerData}
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum._
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.{BusUtil, CityMapUtil, SpeedUtil}
import org.interscity.htc.model.hybrid.util.BusUtil.loadPersonTime
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed

import org.interscity.htc.model.hybrid.entity.state.{BusState, MicroBusState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent

/** Bus actor supporting both MESO and MICRO simulation modes.
  * 
  * Extends the base Bus actor with microscopic simulation capabilities.
  * Buses have unique characteristics:
  * - Larger vehicle length (12m vs 4.5m for cars)
  * - Slower acceleration (1.2 m/s² vs 2.6 m/s²)
  * - Passenger capacity tracking
  * - Bus stop interactions
  * - Lane restrictions (bus lanes)
  * 
  * MESO mode:
  * - Standard mesoscopic behavior with passenger loading/unloading
  * - Aggregate speed calculation
  * 
  * MICRO mode:
  * - Individual positioning with bus-specific parameters
  * - Bus stop interactions at microscopic precision
  * - Lane restrictions enforced
  * - Passenger management continues
  * 
  * @param properties Actor properties
  */
class Bus(
  private val properties: Properties
) extends Movable[BusState](
      properties = properties
    ) {
  
  /** Car-following model (same as car, but with bus parameters).
    */
  private val carFollowingModel: CarFollowingModel = KraussModel()
  
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

  /** Expected tick when red signal phase ends.
    * Prevents stale WaitingSignalState poll ticks from triggering premature leavingLink.
    */
  private var signalWaitUntilTick: Option[Tick] = None

  /** Maximum simulation end tick - vehicles must finish by this tick. */
  private lazy val simulationEndTick: Tick = model.hybrid.util.VehicleSimulationConfig.simulationEndTick

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
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null) {
      logWarn("Bus state is null, cannot process spontaneous event — sending FinishEvent to unblock TimeManager")
      onFinishSpontaneous(None)
      return
    }

    // Safety net: Force-finish vehicles that exceed simulation end time (only when extension is disabled)
    if (!model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
        && currentTick >= simulationEndTick && state.status != Finished) {
      logInfo(s"Bus ${getEntityId} exceeded simulation end time ($simulationEndTick) at tick $currentTick, force-finishing.")
      val finalNode = Option(getCurrentNode).getOrElse(state.destination)
      finishJourney("simulation_time_exceeded", finalNode)
      onFinishSpontaneous(None)
      selfDestruct()
      return
    }
    
    state.status match {
      case Start =>
        report(
          data = Map(
            "event_type" -> "journey_started",
            "vehicle_id" -> getEntityId,
            "bus_id" -> getEntityId,
            "origin" -> state.origin,
            "destination" -> state.destination,
            "route_length" -> state.movableBestRoute.map(_.size).getOrElse(0),
            "tick" -> currentTick
          ),
          label = "journey_started"
        )
        state.status = Ready
        enterLink()
      
      case Ready =>
        enterLink()
      
      case Moving =>
        if (state.isMicroMode) {
          // MICRO mode: check position and handle bus stops
          var shouldLeave = false
          state.microState.foreach { micro =>
            // Check if at bus stop
            checkBusStopAtPosition(micro.positionInLink)
            
            // Check if reached end of link
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
//           requestLoadPassenger()
//           requestUnloadPeopleData()
        }
      
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
      
      case WaitingLoadPassenger | WaitingUnloadPassenger =>
//         if (isEndNodeState && nodeStateMaxTime == event.tick) {
          leavingLink()
//         }
      
      case _ =>
        logInfo(s"Event current status not handled ${state.status}")
        onFinishSpontaneous(Some(currentTick + 1))
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: SignalStateData => handleSignalState(event, d)
      case d: MicroEnterLinkData => handleMicroEnterLink(event, d)
      case d: MicroUpdateData => handleMicroUpdate(event, d)
      case d: MicroLeaveLinkData => handleMicroLeaveLink(event, d)
      case d: BusLoadPassengerData => handleBusLoadPeople(event, d)
      case d: BusUnloadPassengerData => handleUnloadPassenger(event, d)
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
      logInfo(s"Bus ${getEntityId} reached destination: $currentNodeId")
      finishJourney(
        reason = "reached_destination",
        finalNode = Option(currentNodeId).getOrElse("unknown")
      )
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
  
  /** Handle entering MICRO link.
    */
  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit = {
    logDebug(s"Bus entering MICRO link ${data.linkId}, lane ${data.assignedLane}")
    
    currentLinkId = Some(data.linkId)
    linkEntryTick = Some(currentTick)
    
    // Initialize microscopic state with bus parameters
    val initialMicroState = MicroBusState(
      positionInLink = 0.0,
      velocity = state.microState.map(_.velocity).getOrElse(data.speedLimit * 0.7), // Buses slower
      acceleration = 0.0,
      currentLane = data.assignedLane,
      leaderVehicle = None,
      gapToLeader = data.linkLength,
      leaderVelocity = data.speedLimit,
      maxAcceleration = 1.2, // Bus-specific (slower than car)
      maxDeceleration = 3.5,
      minGap = 3.0, // Larger gap
      desiredVelocity = math.min(data.speedLimit, 11.11), // 40 km/h max for bus
      reactionTime = 1.5, // Longer reaction time
      vehicleLength = 12.0, // Bus length
      capacity = state.capacity,
      currentPassengers = state.people.size,
      nextBusStop = findNextBusStop(),
      busLaneRestricted = true, // Prefer bus lanes
      desiredLane = if (data.assignedLane == 2) Some(2) else None, // Prefer lane 2 if bus lane
      laneChangeProgress = 0.0,
      canChangeLane = false // Buses typically stay in lane
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
        "bus_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "lane" -> data.assignedLane,
        "passengers" -> state.people.size,
        "capacity" -> state.capacity,
        "occupancy" -> state.occupancyPercentage,
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
        leaderVelocity = data.leaderVelocity,
        currentPassengers = state.people.size // Update passenger count
      )
      
      state.updateMicroState(updatedMicro)
      sumoArrivalSpeed = data.velocity
      updateHaltingState(data.velocity, sumoCurrentMicroTimeStepSeconds)
      
      log.debug(s"Bus micro update: pos=${data.position}, vel=${data.velocity}, passengers=${state.people.size}")
      
      // Check for bus stop proximity
      checkBusStopAtPosition(data.position)
    }
  }
  
  /** Handle leaving MICRO link.
    */
  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    logDebug(s"Bus leaving MICRO link ${data.linkId}")
    
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
        "bus_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "passengers" -> state.people.size,
        "occupancy" -> state.occupancyPercentage,
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
    logDebug(s"Bus entering MESO link ${event.actorRefId}")
    
    val speed = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )
    val time = if (speed > 0.0) data.linkLength / speed else data.linkLength
    
    state.status = Moving
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
        "bus_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "passengers" -> state.people.size,
        "capacity" -> state.capacity,
        "occupancy" -> state.occupancyPercentage,
        "travel_time" -> time,
        "tick" -> currentTick
      ),
      label = "enter_link"
    )
    
    val exitTick = currentTick + time.toLong
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
        "bus_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "passengers" -> state.people.size,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_link"
    )

    val routeDepleted = state.movableCurrentPath.isEmpty && state.movableBestRoute.forall(_.isEmpty)
    if (routeDepleted && state.status != Finished) {
      finishJourney("reached_destination", state.destination)
      onFinishSpontaneous(None)
      selfDestruct()
    } else {
      onFinishSpontaneous(Some(currentTick + 1))
    }
  }
  
  /** Handle passenger loading.
    */
  private def handleBusLoadPeople(event: ActorInteractionEvent, data: BusLoadPassengerData): Unit = {
    if (data.people.nonEmpty) {
      val nextTickTime = currentTick + loadPersonTime(
        numberOfPorts = state.numberOfPorts,
        numberOfPassengers = data.people.size
      )
      sumoStopTimeSeconds += math.max(0L, nextTickTime - currentTick).toDouble
      scheduleEvent(nextTickTime)
      
      for (person <- data.people) {
        state.people.put(person.id, person)
      }
      
      
      // Report passenger loading
      report(
        data = Map(
          "event_type" -> "bus_load_passengers",
          "bus_id" -> getEntityId,
          "passengers_loaded" -> data.people.size,
          "total_passengers" -> state.people.size,
          "occupancy" -> state.occupancyPercentage,
          "tick" -> currentTick
        ),
        label = "bus_load_passengers"
      )
      
    } else {
    }
  }
  
  /** Handle passenger unloading.
    */
  private def handleUnloadPassenger(event: ActorInteractionEvent, data: BusUnloadPassengerData): Unit = {
    state.countUnloadReceived += 1
    
    if (state.countUnloadReceived == state.people.size) {
      val nextTickTime = currentTick + BusUtil.unloadPersonTime(
        numberOfPassengers = state.people.size,
        numberOfPorts = state.numberOfPorts
      )
      sumoStopTimeSeconds += math.max(0L, nextTickTime - currentTick).toDouble
      scheduleEvent(nextTickTime)
      
      // Report passenger unloading
      report(
        data = Map(
          "event_type" -> "bus_unload_passengers",
          "bus_id" -> getEntityId,
          "tick" -> currentTick
        ),
        label = "bus_unload_passengers"
      )
      
    }
  }
  
  /** Handle signal state.
    */
  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
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
          "vehicle_type" -> "bus",
          "vehicle_id" -> getEntityId,
          "phase" -> data.phase.toString,
          "wait_until_tick" -> data.nextTick,
          "capacity" -> state.capacity,
          "current_passengers" -> state.people.size,
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
  
  override def getNextPath: Option[(String, String)] = {
    state.bestRoute match {
      case Some(path) =>
        if (state.currentPathPosition < path.size) {
          val nextPath = path(state.currentPathPosition)
          state.currentPathPosition += 1
          Some(nextPath)
        } else {
          state.currentPathPosition = 0
          Some(path(state.currentPathPosition))
        }
      case None =>
        None
    }
  }
  
  /** Check if bus is at a bus stop (for MICRO mode).
    */
  private def checkBusStopAtPosition(position: Double): Unit = {
    state.microState.foreach { micro =>
      micro.nextBusStop.foreach { stopId =>
        // Check if close to bus stop (within 10m)
        // This is simplified - actual implementation would query bus stop positions
        log.debug(s"Bus at position $position, next stop: $stopId")
      }
    }
  }
  
  /** Find next bus stop on route.
    */
  private def findNextBusStop(): Option[String] = {
    // Simplified - would actually query route for next bus stop
    state.busStops.headOption.map(_._1)
  }
  
  /** Get current link length.
    */
  private def getCurrentLinkLength: Double = {
    currentLinkId.flatMap { linkId =>
      org.interscity.htc.model.hybrid.util.CityMapUtil.edgeLabelsById.get(linkId).map(_.length)
    }.getOrElse(1000.0)
  }

  private def finishJourney(reason: String, finalNode: String): Unit = {
    report(
      data = Map(
        "event_type" -> "journey_completed",
        "vehicle_id" -> getEntityId,
        "bus_id" -> getEntityId,
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

    reportSumoTripInfo(reason = reason, finalNode = finalNode)
    state.status = Finished
  }

  private def reportSumoTripInfo(reason: String, finalNode: String): Unit = {
    if (sumoTripInfoReported) return

    val plannedDepart = state.startTick
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
        "vehicle_type" -> "bus",
        "vType" -> "bus",
        "origin" -> state.origin,
        "destination" -> state.destination,
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
        "speedFactor" -> 1.0,
        "vaporized" -> vaporized,
        "tick" -> currentTick
      ),
      label = "sumo_tripinfo"
    )

    sumoTripInfoReported = true
  }

  override def onDestruct(event: DestructEvent): Unit = {
    if (state != null && !sumoTripInfoReported && state.status != Finished) {
      val fallbackNode = Option(getCurrentNode)
        .orElse(state.movableCurrentPath.map(_._2))
        .getOrElse("unknown")
      finishJourney("actor_destructed_before_completion", fallbackNode)
    }
  }
}

/** Bus companion object.
  */
object Bus {
  def apply(properties: Properties): Bus = {
    new Bus(properties)
  }
}
