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
import org.interscity.htc.model.hybrid.util.{CityMapUtil, GPSUtil}
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.hybrid.entity.state.{CarState, DriverAttributes, MicroCarState}
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent

import scala.collection.mutable

class Car(
           private val properties: Properties
         ) extends Movable[CarState](
  properties = properties
) with PrivateVehicle[CarState] {

  // A trava absoluta para garantir log único de finalização
  private var journeyFinishedReported: Boolean = false

  private var currentLinkId: Option[String] = None
  private var currentLinkLength: Double = 0.0
  private var linkEntryTick: Option[Tick] = None
  private var mesoExitTick: Option[Tick] = None

  // SUMO TripInfo variables
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

  private lazy val simulationEndTick: Tick = model.hybrid.util.VehicleSimulationConfig.simulationEndTick

  private var signalWaitUntilTick: Option[Tick] = None
  private var signalStateRetryCounter: Int = 0
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
  override protected def getVehicleStatus: MovableStatusEnum = if (state == null) Parked else state.status
  override protected def setVehicleStatus(status: MovableStatusEnum): Unit = if (state != null) state.movableStatus = status
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

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null || state.movableStatus == Parked) {
      onFinishSpontaneous(None)
      return
    }

    if (!model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
      && currentTick >= simulationEndTick && state.movableStatus != Finished) {
      logInfo(s"Car ${getEntityId} exceeded simulation end time ($simulationEndTick) at tick $currentTick, force-finishing.")
      val finalNode = Option(getCurrentNode).getOrElse(state.destination)
      // Notify the Link so it removes this car from vehiclesByLane
      leavingLink()
      finishJourney("simulation_time_exceeded", finalNode)
      onFinishPrivateVehicle(finalNode)
      onFinishSpontaneous(None)
      selfDestruct()
      return
    }

    state.movableStatus match {
      case Moving =>
        if (!state.isMicroMode) {
          mesoExitTick match {
            case Some(exitTick) if currentTick < exitTick =>
              onFinishSpontaneous(Some(exitTick))
            case _ =>
              mesoExitTick = None
              requestSignalState()
          }
        } else {
          // Car is in MICRO mode: driven entirely by MicroUpdate messages from the link.
          // Spontaneous events should not occur in normal MICRO operation (they are stopped
          // by onFinishSpontaneous(None) in handleMicroEnterLink). If one arrives here due to
          // stale Waiting-state ticks or other edge cases, quietly deregister to keep things clean
          // (Bug 1 fix ensures this does NOT cause a spurious hasScheduled=false report).
          onFinishSpontaneous(None)
        }

      case WaitingSignal =>
        signalWaitUntilTick match {
          case Some(waitTick) if currentTick < waitTick =>
            onFinishSpontaneous(Some(waitTick))
          case _ =>
            signalWaitUntilTick = None
            leavingLink()
        }

      case WaitingSignalState =>
        signalStateRetryCounter += 1
        if (signalStateRetryCounter > MaxSignalStateRetries) {
          logWarn(s"${getEntityId} stuck in WaitingSignalState for $signalStateRetryCounter ticks at tick $currentTick (Node not responding). Recovering by leaving link.")
          signalStateRetryCounter = 0
          leavingLink()
        } else {
          requestSignalState()
        }

      case Stopped =>
        onFinishSpontaneous(Some(currentTick + 1))

      case _ =>
        super.actSpontaneous(event)
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

  private def precomputedRoute = state.precomputedRoute
    .map { items =>
      items.flatMap { item =>
        if (item.linkId != null && item.linkId.nonEmpty && item.nodeId != null && item.nodeId.nonEmpty) {
          Some((item.linkId, item.nodeId))
        } else None
      }
    }
    .filter(_.nonEmpty)
    .map(items => mutable.Queue.from(items))

  override def requestRoute(): Unit = {
    if (state.movableStatus == Finished) return

    val precomputedPathQueue = precomputedRoute

    if (precomputedPathQueue.nonEmpty) {
      val fixedRoute = precomputedPathQueue.get
      state.bestRoute = Some(fixedRoute.clone())
      state.bestCost = fixedRoute.size.toDouble
      state.movableBestRoute = Some(fixedRoute)
      state.movableBestCost = state.bestCost
      state.movableStatus = Ready
      state.updateCurrentPath(None)

      reportRouteEvents(fixedRoute, "precomputed")

      if (fixedRoute.nonEmpty) {
        enterLink()
      } else {
        finishJourney("already_at_destination", state.origin)
        onFinishSpontaneous(None)
        selfDestruct()
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

      reportRouteEvents(fixedRoute, "preloaded")

      if (fixedRoute.nonEmpty) {
        enterLink()
      } else {
        finishJourney("already_at_destination", state.origin)
        onFinishSpontaneous(None)
        selfDestruct()
      }
      return
    }

    if (sumoDepartTick.nonEmpty) sumoRerouteNo += 1

    val origin = getTripOrigin.getOrElse(state.origin)
    val destination = getTripDestination.getOrElse(state.destination)

    if (origin == null || destination == null) {
      finishJourney("null_origin_or_destination", state.origin)
      onFinishSpontaneous(None)
      selfDestruct()
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

          reportRouteEvents(pathQueue, "calculated", cost)

          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            finishJourney("already_at_destination", state.origin)
            onFinishSpontaneous(None)
            selfDestruct()
          }

        case None =>
          logError(s"Failed to calculate route from ${state.origin} to ${state.destination}")
          finishJourney("route_calculation_failed", state.origin)
          onFinishSpontaneous(None)
          selfDestruct()
      }
    } catch {
      case e: Exception =>
        logError(s"Exception during route request: ${e.getMessage}", e)
        finishJourney("exception_during_route_request", state.origin)
        onFinishSpontaneous(None)
        selfDestruct()
    }
  }

  private def reportRouteEvents(route: mutable.Queue[(String, String)], source: String, cost: Double = 0.0): Unit = {
    report(
      data = Map(
        "event_type" -> "journey_started",
        "vehicle_id" -> getEntityId,
        "car_id" -> getEntityId,
        "origin" -> state.origin,
        "destination" -> state.destination,
        "route_cost" -> (if(cost > 0) cost else state.bestCost),
        "route_length" -> route.size,
        "tick" -> currentTick,
        "route_source" -> source
      ),
      label = "journey_started"
    )

    report(
      data = Map(
        "event_type" -> "route_planned",
        "car_id" -> getEntityId,
        "route_links" -> route.map(_._1).mkString(","),
        "route_nodes" -> route.map(_._2).mkString(","),
        "tick" -> currentTick,
        "route_source" -> source
      ),
      label = "route_planned"
    )
  }

  private def requestSignalState(): Unit = {
    val currentPathNode = state.movableCurrentPath.map(_._2).orNull
    val routeDepleted = state.movableBestRoute.forall(_.isEmpty)

    if (currentPathNode == null && !routeDepleted) {
      logWarn(s"${getEntityId} requestSignalState with null currentPathNode but non-empty route at tick=$currentTick; recovering to Ready")
      state.movableStatus = Ready
      onFinishSpontaneous(Some(currentTick + 1))
      return
    }

    if (state.destination == currentPathNode || routeDepleted) {
      val currentNodeId = getCurrentNode
      val finalNode = Option(currentPathNode).orElse(Option(currentNodeId)).getOrElse(state.destination)
      // Notify the Link so it removes this car from vehiclesByLane;
      // without this, Link keeps sending MicroUpdateData to a dead actor,
      // causing shard to re-create an uninitialized Car → NPE.
      leavingLink()
      finishJourney("reached_destination", finalNode)
      onFinishPrivateVehicle(finalNode)
      onFinishSpontaneous(None)
      selfDestruct()
    } else {
      state.movableStatus = WaitingSignalState
      val nodeId = getCurrentNode
      if (nodeId != null) {
        CityMapUtil.nodesById.get(nodeId) match {
          case Some(node) =>
            val linkId = getNextLink
            if (linkId != null) {
              sendMessageTo(
                entityId = node.id,
                shardId = node.classType,
                data = RequestSignalStateData(targetLinkId = linkId),
                eventType = EventTypeEnum.RequestSignalState.toString
              )
              onFinishSpontaneous(Some(currentTick + 1))
            } else {
              leavingLink()
            }
          case None => leavingLink()
        }
      } else {
        leavingLink()
      }
    }
  }

  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
    // Guard against stale/duplicate SignalStateData responses caused by the retry mechanism.
    // When a spontaneous tick fires before the node responds, the car retries requestSignalState,
    // generating a second request. Both responses eventually arrive. Without this guard, the second
    // response would call leavingLink() on an already-left link, corrupting the route queue.
    if (state.movableStatus != WaitingSignalState) {
      logDebug(s"${getEntityId}: Ignoring stale SignalStateData (current status=${state.movableStatus}, expected WaitingSignalState). Race condition guard.")
      return
    }
    signalStateRetryCounter = 0
    if (data.phase == Red) {
      state.movableStatus = WaitingSignal
      signalWaitUntilTick = Some(data.nextTick)
      val waitTicks = math.max(0L, data.nextTick - currentTick)
      if (waitTicks > 0) {
        updateHaltingState(speed = 0.0, deltaSeconds = waitTicks.toDouble)
      }

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
    mesoExitTick = None
    signalWaitUntilTick = None
    state.movableStatus = Ready
    super.leavingLink()
  }

  override protected def onFinish(nodeId: String): Unit = {
    onFinishPrivateVehicle(nodeId)
    finishJourney("onFinish_called", nodeId)
    super.onFinish(nodeId)
  }

  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit = {
    currentLinkId = Some(data.linkId)
    currentLinkLength = data.linkLength
    linkEntryTick = Some(currentTick)

    // speedLimit from LinkState is stored in km/h; Link micro physics converts with /3.6
    val speedLimitMs = data.speedLimit / 3.6

    val initialMicroState = MicroCarState(
      positionInLink = 0.0,
      velocity = state.microState.map(_.velocity).getOrElse(speedLimitMs * 0.8),
      acceleration = 0.0,
      currentLane = data.assignedLane,
      leaderVehicle = None,
      gapToLeader = data.linkLength,
      leaderVelocity = speedLimitMs,
      desiredVelocity = speedLimitMs
    )

    state.activateMicroMode(initialMicroState)
    state.movableStatus = Moving
    sumoCurrentMicroTimeStepSeconds = math.max(0.001, data.microTimeStep)
    // Ideal travel time: length(m) / speed(m/s)
    sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, speedLimitMs)
    updateHaltingState(initialMicroState.velocity, 0.0)

    if (sumoDepartTick.isEmpty) {
      sumoDepartTick = Some(currentTick)
      sumoDepartSpeed = initialMicroState.velocity
      sumoDepartLane = Some(s"${data.linkId}_${initialMicroState.currentLane}")
      sumoDepartPos = 0.0
    }

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

    onFinishSpontaneous(None)
  }

  private def handleMicroUpdate(event: ActorInteractionEvent, data: MicroUpdateData): Unit = {
    // MICRO-mode cars don't receive spontaneous events, so they can't rely on actSpontaneous
    // to detect simulation end.  This guard mirrors the same check in actSpontaneous so that
    // a car that is still traversing a MICRO link when the simulation clock expires will
    // cleanly finish its journey instead of silently vanishing.
    if (!model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
        && currentTick >= simulationEndTick && state.movableStatus != Finished) {
      logInfo(s"Car ${getEntityId} exceeded simulation end time ($simulationEndTick) at tick $currentTick in MICRO mode, force-finishing.")
      val finalNode = Option(getCurrentNode).getOrElse(state.destination)
      leavingLink()
      finishJourney("simulation_time_exceeded", finalNode)
      onFinishPrivateVehicle(finalNode)
      onFinishSpontaneous(None)
      selfDestruct()
      return
    }

    state.microState.foreach { micro =>
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

      if (data.position >= getCurrentLinkLength && state.movableStatus == Moving) {
        requestSignalState()
      }
    }
  }

  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    // Only process if this car is actively on this exact link. This covers two cases:
    //  1. Race condition: MicroLeaveLinkData from a previous link arrives after the car has
    //     already moved to the next link (currentLinkId is Some(otherLink)).
    //  2. Stale delivery to a shard-recreated car: when forceDestructActiveActors destructs
    //     a MICRO-mode car, an in-flight MicroLeaveLinkData queued in the shard is delivered
    //     to the new instance which has currentLinkId == None. Using !contains instead of
    //     isDefined && !contains ensures we also discard the None case.
    if (!currentLinkId.contains(data.linkId)) {
      logWarn(s"${getEntityId}: Ignoring stale MicroLeaveLink for link ${data.linkId} " +
        s"(car is on link ${currentLinkId.getOrElse("none")}). Discarded.")
      return
    }

    val travelTime = linkEntryTick.map(entryTick => currentTick - entryTick).getOrElse(0L)

    state.distance += data.distanceTraveled
    sumoArrivalSpeed = data.finalVelocity
    sumoArrivalLane = Some(s"${data.linkId}_${state.microState.map(_.currentLane).getOrElse(0)}")
    sumoArrivalPos = data.finalPosition
    updateHaltingState(data.finalVelocity, 0.0)

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

    state.deactivateMicroMode()
    currentLinkId = None
    currentLinkLength = 0.0
    linkEntryTick = None

    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit = {
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

    val exitTick = currentTick + Math.ceil(time).toLong
    mesoExitTick = Some(exitTick)
    onFinishSpontaneous(Some(exitTick))
  }

  override def actHandleReceiveLeaveLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit = {
    state.distance += data.linkLength
    sumoArrivalSpeed = 0.0
    sumoArrivalLane = Some(s"${event.actorRefId}_0")
    sumoArrivalPos = data.linkLength
    updateHaltingState(0.0, 0.0)

    currentLinkId = None
    currentLinkLength = 0.0
    linkEntryTick = None
    mesoExitTick = None

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

    if (routeDepleted && state.movableStatus != Finished) {
      finishJourney("reached_destination", state.destination)
      onFinishPrivateVehicle(state.destination)
      onFinishSpontaneous(None)
      selfDestruct()
    } else {
      onFinishSpontaneous(Some(currentTick + 1))
    }
  }

  private def finishJourney(reason: String, finalNode: String): Unit = {
    if (journeyFinishedReported) return
    journeyFinishedReported = true

    val destination = getTripDestination.getOrElse(state.destination)
    val origin = getTripOrigin.getOrElse(state.origin)

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

  private def getCurrentLinkLength: Double = {
    if (currentLinkLength > 0.0) currentLinkLength else 1000.0
  }

  override protected def applyDriverAttributes(attrs: DriverAttributes): Unit = {
    super.applyDriverAttributes(attrs)

    state.microState.foreach { micro =>
      val updatedMicro = micro.copy(
        desiredVelocity = micro.desiredVelocity * attrs.maxSpeedFactor,
        reactionTime = attrs.reactionTime,
        minGap = micro.minGap * attrs.minGapFactor,
        maxAcceleration = micro.maxAcceleration * (0.8 + 0.4 * attrs.aggressiveness)
      )
      state.updateMicroState(updatedMicro)
    }
  }

  override def onDestruct(event: DestructEvent): Unit = {
    if (state == null) return

    val fallbackNode = Option(getCurrentNode)
      .orElse(state.movableCurrentPath.map(_._2))
      .getOrElse(state.origin)

    finishJourney("actor_destructed_before_completion", fallbackNode)
  }
}

object Car {
  def apply(properties: Properties): Car = new Car(properties)
}