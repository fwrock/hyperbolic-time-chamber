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
import org.interscity.htc.core.metrics.model.hybrid.{ GPSMetrics, MovableMetrics }
import core.actor.trace.ActorTrace
import core.util.StringPool
import model.hybrid.support.car.CarJourneyReporter

import org.htc.protobuf.core.entity.event.control.execution.DestructEvent

import scala.collection.mutable

class Car(
  private val properties: Properties
) extends Movable[CarState](
      properties = properties
    )
    with PrivateVehicle[CarState] {

  override protected def internStateStrings(s: CarState): CarState = {
    val copied = s.copy(
      origin      = StringPool.intern(s.origin),
      destination = StringPool.intern(s.destination)
    )
    copied.movableStatus             = s.movableStatus
    copied.movableBestRoute          = s.movableBestRoute
    copied.movableCurrentPath        = s.movableCurrentPath
    copied.movableCurrentNode        = s.movableCurrentNode
    copied.movableBestCost           = s.movableBestCost
    copied.movableReachedDestination = s.movableReachedDestination
    copied
  }

  private var currentLinkId: Option[String] = None
  private var currentLinkLength: Double = 0.0
  private var linkEntryTick: Option[Tick] = None
  private var mesoExitTick: Option[Tick] = None

  private lazy val simulationEndTick: Tick =
    model.hybrid.util.VehicleSimulationConfig.simulationEndTick

  private var signalWaitUntilTick: Option[Tick] = None
  private var signalStateRetryCounter: Int = 0
  private val MaxSignalStateRetries: Int = 100

  private lazy val journeyReporter = new CarJourneyReporter(
    reportFn        = (data, label) => report(data = data, label = label),
    entityIdFn      = () => getEntityId,
    currentTickFn   = () => currentTick,
    tripOriginFn    = () => getTripOrigin,
    tripDestFn      = () => getTripDestination,
    tripStartTickFn = () => getTripStartTick,
    driverAttrsFn   = () => getDriverAttributes
  )

  private lazy val staleEventLogEvery: Int =
    sys.env
      .get("HTC_CAR_STALE_EVENT_LOG_EVERY")
      .flatMap(v => scala.util.Try(v.toInt).toOption)
      .orElse(scala.util.Try(config.getInt("htc.car.stale-event-log-every")).toOption)
      .filter(_ > 0)
      .getOrElse(100)

  private var staleEventLogCount: Long = 0L

  private def logStaleEventDebug(message: String): Unit = {
    staleEventLogCount += 1
    if (staleEventLogCount % staleEventLogEvery == 0L)
      logDebug(s"$message [sample=$staleEventLogCount]")
  }

  override protected def getVehicleStatus: MovableStatusEnum =
    if (state == null) Parked else state.status
  override protected def setVehicleStatus(status: MovableStatusEnum): Unit =
    if (state != null) state.status = status
  override protected def getActorCurrentTick: Tick = currentTick
  override protected def getActorShardId: String = getShardId
  override protected def getActorEntityId: String = getEntityId
  override protected def scheduleNextTick(nextTick: Option[Tick]): Unit = onFinishSpontaneous(
    nextTick
  )
  override protected def selfDestructVehicle(): Unit                     = selfDestruct()
  override protected def isVehicleStateNull: Boolean                     = state == null
  override protected def getCurrentDistance: Double = if (state == null) 0.0 else state.distance
  override protected def sendVehicleMessage(
    entityId: String,
    shardId: String,
    data: AnyRef,
    eventType: String,
    actorType: CreationTypeEnum
  ): Unit =
    sendMessageTo(
      entityId = entityId,
      shardId = shardId,
      data = data,
      eventType = eventType,
      actorType = actorType
    )
  override protected def logVehicleInfo(message: String): Unit = logInfo(message)
  override protected def logVehicleWarn(message: String): Unit = logWarn(message)
  override protected def logVehicleDebug(message: String): Unit = logDebug(message)
  override protected def registerOnTimeManager(tick: Tick): Unit = scheduleEvent(tick)

  /** Pre-load route pre-computed by ModeChoiceStrategy so requestRoute() skips a second A*.
    */
  override protected def applyPrecomputedRoute(route: List[(String, String)]): Unit =
    state.bestRoute = Some(scala.collection.mutable.Queue(route: _*))

  /** Reset all per-trip tracking variables so metrics start fresh for each new trip. Called by
    * PrivateVehicle.handleStartTrip before each activation. Critical for person-centric vehicles
    * that serve multiple trips without being destroyed.
    */
  override protected def resetTripState(): Unit = {
    if (state == null) return
    currentLinkId = None
    currentLinkLength = 0.0
    linkEntryTick = None
    mesoExitTick = None
    journeyReporter.reset()
    signalWaitUntilTick = None
    signalStateRetryCounter = 0
    state.bestRoute = None
    state.precomputedRoute = None
    state.deactivateMicroMode()
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null || state.status == Parked) {
      onFinishSpontaneous(None)
      return
    }

    if (
      !model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
      && currentTick >= simulationEndTick && state.status != Finished
    ) {
      logWarn(
        s"Car $getEntityId exceeded simulation end time ($simulationEndTick) at tick $currentTick, force-finishing."
      )
      val finalNode = Option(getCurrentNode).getOrElse(state.destination)
      finishJourney("simulation_time_exceeded", finalNode)
      onFinishPrivateVehicle(finalNode)
      onFinishSpontaneous(None)
      selfDestruct()
      return
    }

    state.status match {
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
          logWarn(
            s"$getEntityId stuck in WaitingSignalState for $signalStateRetryCounter ticks at tick $currentTick (Node not responding). Recovering by leaving link."
          )
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

    if (state == null) {
      event.data match {
        case _: MicroLeaveLinkData | _: MicroUpdateData =>
          logStaleEventDebug(
            s"$getEntityId received stale MICRO event with null state, discarding: ${event.eventType}"
          )
        case _: LinkInfoData =>
          logStaleEventDebug(
            s"$getEntityId received stale MESO link event with null state, discarding: ${event.eventType}"
          )
        case _ =>
          logWarn(
            s"$getEntityId received interaction event with null state, discarding: ${event.eventType}"
          )
      }
      return
    }

    event.data match {
      case d: SignalStateData    => handleSignalState(event, d)
      case d: MicroEnterLinkData => handleMicroEnterLink(event, d)
      case d: MicroUpdateData    => handleMicroUpdate(event, d)
      case d: MicroLeaveLinkData => handleMicroLeaveLink(event, d)
      case _                     => super.actInteractWith(event)
    }
  }


  override def requestRoute(): Unit = {
    if (state.status == Finished) return

    val precomputedPathQueue = state.precomputedRoute
      .map { items =>
        items.flatMap { item =>
          if (item.linkId != null && item.linkId.nonEmpty && item.nodeId != null && item.nodeId.nonEmpty) {
            Some((item.linkId, item.nodeId))
          } else None
        }
      }
      .filter(_.nonEmpty)
      .map(items => mutable.Queue.from(items))

    if (precomputedPathQueue.nonEmpty) {
      val fixedRoute = precomputedPathQueue.get
      state.bestRoute = Some(fixedRoute.clone())
      state.bestCost = fixedRoute.size.toDouble
      state.status = Ready
      state.updateCurrentPath(None)

      state.precomputedRoute = None

      GPSMetrics.routeSource.labels("precomputed").inc()
      journeyReporter.reportRouteEvents(fixedRoute, "precomputed", state.origin, state.destination, state.bestCost)

      if (fixedRoute.nonEmpty) {
        enterLink()
      } else {
        val tripOrigin = getTripOrigin.getOrElse(state.origin)
        finishAndCleanup("already_at_destination", tripOrigin)
      }
      return
    }

    if (state.bestRoute.nonEmpty) {
      val fixedRoute = state.bestRoute.get
      state.bestCost = fixedRoute.size.toDouble
      state.status = Ready
      state.updateCurrentPath(None)

      GPSMetrics.routeSource.labels("preloaded").inc()
      journeyReporter.reportRouteEvents(fixedRoute, "preloaded", state.origin, state.destination, state.bestCost)

      if (fixedRoute.nonEmpty) {
        enterLink()
      } else {
        val tripOrigin = getTripOrigin.getOrElse(state.origin)
        finishAndCleanup("already_at_destination", tripOrigin)
      }
      return
    }

    if (journeyReporter.sumoDepartTick.nonEmpty) journeyReporter.sumoRerouteNo += 1

    val origin = getTripOrigin.getOrElse(state.origin)
    val destination = getTripDestination.getOrElse(state.destination)

    if (origin == null || destination == null) {
      val tripOrigin = getTripOrigin.getOrElse(state.origin)
      finishAndCleanup("null_origin_or_destination", tripOrigin)
      return
    }

    try
      GPSUtil.calcRouteCompact(originId = origin, destinationId = destination, maxExpansions = Int.MaxValue) match {
        case Some((cost, pathQueue)) =>
          GPSMetrics.routeSource.labels("gps_calculated").inc()
          state.bestCost = cost
          state.bestRoute = Some(pathQueue)
          state.status = Ready
          state.updateCurrentPath(None)

          journeyReporter.reportRouteEvents(pathQueue, "calculated", state.origin, state.destination, state.bestCost, cost)

          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            finishAndCleanup("already_at_destination", origin)
          }

        case None =>
          GPSMetrics.gpsCannotFindRoute.labels("car").inc()
          logError(s"Failed to calculate route from $origin to $destination")
          finishAndCleanup("teleported", destination, wasTeleported = true)
      }
    catch {
      case e: Exception =>
        logError(s"Exception during route request: ${e.getMessage}", e)
        finishAndCleanup("exception_during_route_request", origin)
    }
  }

  private def finishAndCleanup(reason: String, finalNode: String, wasTeleported: Boolean = false): Unit = {
    finishJourney(reason, finalNode)
    onFinishPrivateVehicle(finalNode, wasTeleported)
    onFinishSpontaneous(None)
    if (!isPersonCentric) selfDestruct()
  }

  private def reportRouteEvents(
    route: mutable.Queue[(String, String)],
    source: String,
    cost: Double = 0.0
  ): Unit =
    journeyReporter.reportRouteEvents(route, source, state.origin, state.destination, state.bestCost, cost)

  private def requestSignalState(): Unit = {
    val currentPathNode = state.currentPath.map(_._2).orNull
    val routeDepleted = state.bestRoute.forall(_.isEmpty)

    if (currentPathNode == null && !routeDepleted) {
      logWarn(
        s"$getEntityId requestSignalState with null currentPathNode but non-empty route at tick=$currentTick; recovering to Ready"
      )
      state.status = Ready
      onFinishSpontaneous(Some(currentTick + 1))
      return
    }

    if (getTripDestination.getOrElse(state.destination) == currentPathNode || routeDepleted) {
      // NOTE: leavingLink() already calls onFinish(nextNodeId) internally, which invokes
      // onFinishPrivateVehicle() (sends TripCompletedData to Person) and finishJourney().
      // It also calls onFinishSpontaneous(None). Do NOT duplicate those calls here —
      // doing so would send a second TripCompletedData to Person, causing it to skip an
      // activity and schedule itself at two different future ticks.
      leavingLink()
      if (!isPersonCentric) selfDestruct()
    } else {
      state.status = WaitingSignalState
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
          case None =>
            leavingLink()
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
    if (state.status != WaitingSignalState) {
      logStaleEventDebug(
        s"$getEntityId: Ignoring stale SignalStateData (current status=${state.status}, expected WaitingSignalState). Race condition guard."
      )
      return
    }
    signalStateRetryCounter = 0
    if (data.phase == Red) {
      state.status = WaitingSignal
      signalWaitUntilTick = Some(data.nextTick)
      val waitTicks = math.max(0L, data.nextTick - currentTick)
      if (waitTicks > 0) {
        val waitSeconds = waitTicks.toDouble
        journeyReporter.updateHaltingState(speed = 0.0, deltaSeconds = waitSeconds)
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
    state.status = Ready
    super.leavingLink()
  }

  override protected def onFinish(nodeId: String): Unit = {
    finishJourney("onFinish_called", nodeId)
    onFinishPrivateVehicle(nodeId)
    if (!isPersonCentric) {
      if (state.destination == nodeId) state.movableReachedDestination = true
      state.movableStatus = Finished
      onFinishSpontaneous(None, destruct = true)
    }
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
    state.status = Moving
    journeyReporter.sumoCurrentMicroTimeStepSeconds = math.max(0.001, data.microTimeStep)
    // Ideal travel time: length(m) / speed(m/s)
    journeyReporter.sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, speedLimitMs)
    // NOTE: Don't track halting state here - Link tracks it during sub-ticks and sends
    // accumulated waitingTimeSeconds in MicroLeaveLinkData

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick = Some(currentTick)
      journeyReporter.sumoDepartSpeed = initialMicroState.velocity
      journeyReporter.sumoDepartLane = Some(s"${data.linkId}_${initialMicroState.currentLane}")
      journeyReporter.sumoDepartPos = 0.0
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
    if (
      !model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
      && currentTick >= simulationEndTick && state.status != Finished
    ) {
      logDebug(
        s"Car $getEntityId exceeded simulation end time ($simulationEndTick) at tick $currentTick in MICRO mode, force-finishing."
      )
      val finalNode = Option(getCurrentNode).getOrElse(state.destination)
      finishJourney("simulation_time_exceeded", finalNode)
      onFinishPrivateVehicle(finalNode)
      onFinishSpontaneous(None)
      selfDestruct()
      return
    }

    state.microState.foreach {
      micro =>
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
        journeyReporter.sumoArrivalSpeed = data.velocity
    }
  }

  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    if (!currentLinkId.contains(data.linkId)) {
      logWarn(
        s"$getEntityId: Ignoring stale MicroLeaveLink for link ${data.linkId} " +
          s"(car is on link ${currentLinkId.getOrElse("none")}). Discarded."
      )
      return
    }

    val travelTime = linkEntryTick
      .map(
        entryTick => currentTick - entryTick
      )
      .getOrElse(0L)

    state.distance += data.distanceTraveled
    journeyReporter.sumoArrivalSpeed = data.finalVelocity
    journeyReporter.sumoArrivalLane = Some(s"${data.linkId}_${state.microState.map(_.currentLane).getOrElse(0)}")
    journeyReporter.sumoArrivalPos = data.finalPosition

    journeyReporter.sumoWaitingTimeSeconds += data.waitingTimeSeconds
    if (data.waitingTimeSeconds > 0.0) {
      journeyReporter.sumoWaitingCount += 1
    }

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
        "waiting_time_seconds" -> data.waitingTimeSeconds,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_micro_link"
    )

    state.deactivateMicroMode()
    currentLinkId = None
    currentLinkLength = 0.0
    linkEntryTick = None

    requestSignalState()
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
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
    state.status = Moving
    journeyReporter.sumoIdealTravelTimeSeconds += data.linkLength / math.max(0.1, data.linkFreeSpeed)

    if (journeyReporter.sumoDepartTick.isEmpty) {
      journeyReporter.sumoDepartTick = Some(currentTick)
      journeyReporter.sumoDepartSpeed = speed
      journeyReporter.sumoDepartLane = Some(s"${event.actorRefId}_0")
      journeyReporter.sumoDepartPos = 0.0
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

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    // Guard: this callback arrives AFTER leavingLink() was called. For the last link of a
    // trip, the car already finalized the journey in requestSignalState() (or actHandleReceiveLeaveLinkInfo
    // for intermediate links) before this response arrived from the Link. Discard to prevent
    // double distance accumulation, double finishJourney(), and spurious onFinishSpontaneous().
    if (state.status == Parked || state.status == Finished) {
      logStaleEventDebug(
        s"$getEntityId: Discarding stale ReceiveLeaveLinkInfo for link ${event.actorRefId} " +
          s"(status=${state.status}, trip already finalized)."
      )
      return
    }

    state.distance += data.linkLength
    journeyReporter.sumoArrivalSpeed = 0.0
    journeyReporter.sumoArrivalLane = Some(s"${event.actorRefId}_0")
    journeyReporter.sumoArrivalPos = data.linkLength

    linkEntryTick.foreach {
      entryTick =>
        val travelTimeTicks = currentTick - entryTick
        val travelTimeSeconds = travelTimeTicks.toDouble // 1 tick = 1 second

        val actualSpeed = if (travelTimeSeconds > 0) {
          data.linkLength / travelTimeSeconds // m/s
        } else {
          0.0
        }
        
        journeyReporter.updateHaltingState(actualSpeed, travelTimeSeconds)
    }

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

    val routeDepleted = state.currentPath.isEmpty && state.bestRoute.forall(_.isEmpty)

    if (routeDepleted && state.status != Finished) {
      val tripDest = getTripDestination.getOrElse(state.destination)
      finishAndCleanup("reached_destination", tripDest)
    } else {
      onFinishSpontaneous(Some(currentTick + 1))
    }
  }

  private def finishJourney(reason: String, finalNode: String): Unit =
    journeyReporter.finishJourney(reason, finalNode, state)

  override protected def applyDriverAttributes(attrs: DriverAttributes): Unit = {
    super.applyDriverAttributes(attrs)

    state.microState.foreach {
      micro =>
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

    if (state.status != Finished) {
      val fallbackNode = Option(getCurrentNode)
        .orElse(state.movableCurrentPath.map(_._2))
        .getOrElse(state.origin)
      journeyReporter.finishJourney("actor_destructed_before_completion", fallbackNode, state)
    }
    state.movableBestRoute = None
    state.movableCurrentPath = None
    state.microState = None
  }
}

object Car {
  def apply(properties: Properties): Car = new Car(properties)
}
