package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.types.Tick

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.hybrid.entity.event.data.bus.{ BusLoadPassengerData, BusRequestPassengerData, BusRequestUnloadPassengerData, BusUnloadPassengerData }
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum._
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.{ BusUtil, CityMapUtil, SpeedUtil }
import org.interscity.htc.model.hybrid.util.BusUtil.loadPersonTime
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed

import org.interscity.htc.model.hybrid.entity.state.{ BusState, MicroBusState }
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.core.metrics.model.hybrid.{ BusMetrics, MovableMetrics }
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import core.actor.trace.ActorTrace
import core.util.StringPool

/** Bus actor supporting both MESO and MICRO simulation modes.
  *
  * Extends the base Bus actor with microscopic simulation capabilities. Buses have unique
  * characteristics:
  *   - Larger vehicle length (12m vs 4.5m for cars)
  *   - Slower acceleration (1.2 m/s² vs 2.6 m/s²)
  *   - Passenger capacity tracking
  *   - Bus stop interactions
  *   - Lane restrictions (bus lanes)
  *
  * MESO mode:
  *   - Standard mesoscopic behavior with passenger loading/unloading
  *   - Aggregate speed calculation
  *
  * MICRO mode:
  *   - Individual positioning with bus-specific parameters
  *   - Bus stop interactions at microscopic precision
  *   - Lane restrictions enforced
  *   - Passenger management continues
  *
  * @param properties
  *   Actor properties
  */
class Bus(
  private val properties: Properties
) extends Movable[BusState](
      properties = properties
    ) {

  override protected def internStateStrings(s: BusState): BusState = {
    s.busStops = s.busStops.map { case (k, v) => StringPool.intern(k) -> StringPool.intern(v) }
    val copied = s.copy(
      label       = StringPool.intern(s.label),
      origin      = StringPool.intern(s.origin),
      destination = StringPool.intern(s.destination)
    )
    // copy() only replicates BusState constructor params; restore MovableState vars
    // that are not in the constructor (otherwise they reset to their defaults).
    copied.movableStatus             = s.movableStatus
    copied.movableBestRoute          = s.movableBestRoute
    copied.movableCurrentPath        = s.movableCurrentPath
    copied.movableCurrentNode        = s.movableCurrentNode
    copied.movableBestCost           = s.movableBestCost
    copied.movableReachedDestination = s.movableReachedDestination
    copied
  }

  /** Current link being traversed.
    */
  private var currentLinkId: Option[String] = None

  /** Link entry tick.
    */
  private var linkEntryTick: Option[Tick] = None

  /** MESO exit tick — the tick at which link traversal completes. Used to prevent stale
    * Waiting-poll ticks from triggering premature requestSignalState.
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
  private var journeyFinishedReported: Boolean = false

  private lazy val microUpdateLogEvery: Int =
    sys.env
      .get("HTC_BUS_MICRO_UPDATE_LOG_EVERY")
      .flatMap(v => scala.util.Try(v.toInt).toOption)
      .orElse(scala.util.Try(config.getInt("htc.bus.micro-update-log-every")).toOption)
      .filter(_ > 0)
      .getOrElse(200)

  private lazy val busStopProbeLogEvery: Int =
    sys.env
      .get("HTC_BUS_STOP_PROBE_LOG_EVERY")
      .flatMap(v => scala.util.Try(v.toInt).toOption)
      .orElse(scala.util.Try(config.getInt("htc.bus.stop-probe-log-every")).toOption)
      .filter(_ > 0)
      .getOrElse(500)

  private var microUpdateLogCount: Long = 0L
  private var busStopProbeLogCount: Long = 0L

  private def restoreRouteIfMissing(context: String): Unit = {
    if (state != null && state.movableBestRoute.forall(_.isEmpty) && state.storedBestRoute.nonEmpty) {
      val restored = scala.collection.mutable.Queue(state.storedBestRoute.get: _*)
      state.movableBestRoute = Some(restored)
      if (state.currentPathPosition >= restored.size) {
        state.currentPathPosition = 0
      }
      logWarn(
        s"Bus ${getEntityId} restored route from storedBestRoute during $context (${restored.size} segments)"
      )
    }
  }

  /** Expected tick when red signal phase ends. Prevents stale WaitingSignalState poll ticks from
    * triggering premature leavingLink.
    */
  private var signalWaitUntilTick: Option[Tick] = None

  /** Counts successive spontaneous ticks spent in WaitingSignalState without a node response.
    * Mirrors Car's recovery mechanism: if the node never replies, the bus force-leaves the link
    * instead of deadlocking indefinitely.
    */
  private var signalStateRetryCounter: Int = 0
  private val MaxSignalStateRetries: Int = 100

  /** Number of passengers asked to unload at current stop. Used to track when all responses
    * arrived.
    */
  private var expectedUnloadResponses: Int = 0

  /** Node ID of the stop the bus just arrived at (saved before leavingLink clears currentPath). */
  private var currentStopNode: Option[String] = None

  /** Counts every bus stop arrival (for cycle diagnostics). */
  private var stopArrivalCount: Int = 0

  /** Maximum simulation end tick - vehicles must finish by this tick. */
  private lazy val simulationEndTick: Tick =
    model.hybrid.util.VehicleSimulationConfig.simulationEndTick

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
      logWarn(
        "Bus state is null, cannot process spontaneous event — sending FinishEvent to unblock TimeManager"
      )
      onFinishSpontaneous(None)
      return
    }

    if (
      !model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
      && currentTick >= simulationEndTick && state.status != Finished
    ) {
      logWarn(
        s"Bus ${getEntityId} exceeded simulation end time ($simulationEndTick) at tick $currentTick, force-finishing."
      )
      val finalNode = Option(getCurrentNode).getOrElse(state.destination)
      finishJourney("simulation_time_exceeded", finalNode)
      onFinishSpontaneous(None, destruct = true)
      return
    }

    state.status match {
      // RouteWaiting is the default movableStatus when Jackson deserializes BusState without
      // restoring the Start value (movableStatus is not a BusState constructor parameter).
      // Treat it identically to Start: restore the stored route and begin the journey.
      case Start | RouteWaiting =>
        restoreRouteIfMissing("Start")
        MovableMetrics.journeysStarted.labels(getClass.getSimpleName).inc()
        report(
          data = Map(
            "event_type" -> "journey_started",
            "vehicle_id" -> getEntityId,
            "bus_id" -> getEntityId,
            "origin" -> state.origin,
            "destination" -> state.destination,
            "route_length" -> state.bestRoute.map(_.size).getOrElse(0),
            "tick" -> currentTick
          ),
          label = "journey_started"
        )
        state.status = Ready
        ActorTrace.trace(getEntityId, currentTick, "bus_journey_started", // #actor-trace
          s"origin=${state.origin} destination=${state.destination} route=${state.bestRoute.map(_.size).getOrElse(0)} label=${state.label}") // #actor-trace
        enterLink()

      case Ready =>
        val nodeId = currentStopNode.orNull
        if (nodeId != null && findBusStopAtNode(nodeId).isDefined) {
          stopArrivalCount += 1
          ActorTrace.trace(getEntityId, currentTick, "bus_stop_arrived", // #actor-trace
            s"node=$nodeId stop=${findBusStopAtNode(nodeId).getOrElse("none")} passengers=${state.people.size} label=${state.label}") // #actor-trace
          if (stopArrivalCount % 100 == 0 || stopArrivalCount <= 5) {
//            logInfo(s"[BUS-CYCLE] ${getEntityId} stop #$stopArrivalCount at $nodeId tick=$currentTick")
          }
          requestUnloadPeopleData()
        } else {
          currentStopNode = None
          enterLink()
        }

      case Moving =>
        if (state.isMicroMode) {
          var shouldLeave = false
          state.microState.foreach {
            micro =>
              checkBusStopAtPosition(micro.positionInLink)

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
          mesoExitTick match {
            case Some(exitTick) if currentTick < exitTick =>
//              logInfo(s"[BUS-MOVING] ${getEntityId} waiting in MESO link, exitTick=$exitTick currentTick=$currentTick")
              onFinishSpontaneous(Some(exitTick))
            case _ =>
//              logInfo(s"[BUS-MOVING] ${getEntityId} MESO link traversed, calling requestSignalState at tick=$currentTick mesoExitTick=$mesoExitTick")
              mesoExitTick = None
              requestSignalState()
          }
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

      case WaitingLoadPassenger =>
//        logInfo(s"[BUS-CYCLE] ${getEntityId} WaitingLoadPassenger->enterLink at tick=$currentTick stopCount=$stopArrivalCount")
        currentStopNode = None
        enterLink()

      case WaitingUnloadPassenger =>
        requestLoadPassenger()

      case _ =>
        super.actSpontaneous(event)
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: SignalStateData        => handleSignalState(event, d)
      case d: MicroEnterLinkData     => handleMicroEnterLink(event, d)
      case d: MicroUpdateData        => handleMicroUpdate(event, d)
      case d: MicroLeaveLinkData     => handleMicroLeaveLink(event, d)
      case d: BusLoadPassengerData   => handleBusLoadPeople(event, d)
      case d: BusUnloadPassengerData => handleUnloadPassenger(event, d)
      case _                         => super.actInteractWith(event)
    }

  /** Request signal state from node before leaving link.
    */
  private def requestSignalState(): Unit = {
    restoreRouteIfMissing("requestSignalState")
    val routeDepleted = state.bestRoute.forall(_.isEmpty)
    if (routeDepleted) {
      val currentNodeId = getCurrentNode
      logDebug(s"Bus ${getEntityId} reached destination: $currentNodeId")
      finishJourney(
        reason = "reached_destination",
        finalNode = Option(currentNodeId).getOrElse("unknown")
      )
      onFinishSpontaneous(None, destruct = true)
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

    val initialMicroState = MicroBusState(
      positionInLink = 0.0,
      velocity = state.microState.map(_.velocity).getOrElse(data.speedLimit * 0.7),
      acceleration = 0.0,
      currentLane = data.assignedLane,
      leaderVehicle = None,
      gapToLeader = data.linkLength,
      leaderVelocity = data.speedLimit,
      maxAcceleration = 1.2,
      maxDeceleration = 3.5,
      minGap = 3.0,
      desiredVelocity = math.min(data.speedLimit, 11.11) * math.max(0.5, math.min(1.5, state.speedFactor)),
      reactionTime = 1.5,
      vehicleLength = 12.0,
      capacity = state.capacity,
      currentPassengers = state.people.size,
      nextBusStop = findNextBusStop(),
      busLaneRestricted = true,
      desiredLane = if (data.assignedLane == 2) Some(2) else None,
      laneChangeProgress = 0.0,
      canChangeLane = false
    )

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
  private def handleMicroUpdate(event: ActorInteractionEvent, data: MicroUpdateData): Unit =
    state.microState.foreach {
      micro =>
        val updatedMicro = micro.copy(
          positionInLink = data.position,
          velocity = data.velocity,
          acceleration = data.acceleration,
          currentLane = data.currentLane,
          leaderVehicle = data.leaderVehicle,
          gapToLeader = data.gapToLeader,
          leaderVelocity = data.leaderVelocity,
          currentPassengers = state.people.size
        )

        state.updateMicroState(updatedMicro)
        sumoArrivalSpeed = data.velocity
        updateHaltingState(data.velocity, sumoCurrentMicroTimeStepSeconds)

        microUpdateLogCount += 1
        if (microUpdateLogCount % microUpdateLogEvery == 0L)
          log.debug(
            s"Bus micro update[$microUpdateLogCount]: pos=${data.position}, vel=${data.velocity}, passengers=${state.people.size}"
          )

        checkBusStopAtPosition(data.position)
    }

  /** Handle leaving MICRO link.
    */
  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    logDebug(s"Bus leaving MICRO link ${data.linkId}")

    val travelTime = linkEntryTick
      .map(
        entryTick => currentTick - entryTick
      )
      .getOrElse(0L)

    state.distance += data.distanceTraveled
    sumoArrivalSpeed = data.finalVelocity
    sumoArrivalLane = Some(s"${data.linkId}_${state.microState.map(_.currentLane).getOrElse(0)}")
    sumoArrivalPos = data.finalPosition
    updateHaltingState(data.finalVelocity, 0.0)

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

    // Mirror what leavingLink() does in MESO: capture the destination node before
    // deactivating micro state so that actSpontaneous(Ready) can check for a bus stop.
    currentStopNode = state.currentPath.map(_._2)

    state.deactivateMicroMode()
    state.status = Ready
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
//    logInfo(s"[BUS-MESO-ENTER] ${getEntityId} entering MESO link ${event.actorRefId} tick=$currentTick status=${state.status}")

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

    restoreRouteIfMissing("ReceiveLeaveLinkInfo")
    val routeDepleted = state.currentPath.isEmpty && state.bestRoute.forall(_.isEmpty)
//    logInfo(s"[BUS-LEAVE-ACK] ${getEntityId} LeaveLinkInfo from ${event.actorRefId} tick=$currentTick status=${state.status} routeDepleted=$routeDepleted currentPath=${state.currentPath} bestRoute=${state.bestRoute.map(_.size)}")
    if (routeDepleted && state.status != Finished) {
      finishJourney("reached_destination", state.destination)
      onFinishSpontaneous(None, destruct = true)
    } else {
      onFinishSpontaneous(Some(currentTick + 1))
    }
  }

  /** Handle passenger loading.
    */
  private def handleBusLoadPeople(event: ActorInteractionEvent, data: BusLoadPassengerData): Unit =
    if (data.people.nonEmpty) {
      val nextTickTime = currentTick + loadPersonTime(
        numberOfPorts = state.numberOfPorts,
        numberOfPassengers = data.people.size
      )
      sumoStopTimeSeconds += math.max(0L, nextTickTime - currentTick).toDouble

      for (person <- data.people)
        state.people.put(person.id, person)

      BusMetrics.passengersBoarded.labels(state.label).inc(data.people.size)
      BusMetrics.activePassengers.inc(data.people.size)
      ActorTrace.trace(getEntityId, currentTick, "bus_passengers_loaded", // #actor-trace
        s"loaded=${data.people.size} total=${state.people.size} occupancy=${state.occupancyPercentage}% label=${state.label}") // #actor-trace

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

      state.status = WaitingLoadPassenger
      scheduleEvent(nextTickTime)
    } else {
      state.status = WaitingLoadPassenger
      scheduleEvent(currentTick + 1)
    }

  /** Handle passenger unloading.
    */
  private def handleUnloadPassenger(
    event: ActorInteractionEvent,
    data: BusUnloadPassengerData
  ): Unit = {
    state.countUnloadReceived += 1

    if (data.isArrival) {
      state.people.remove(event.actorRefId)
      state.countUnloadPassenger += 1
    }

    if (state.countUnloadReceived >= expectedUnloadResponses) {
      val unloadedCount = state.countUnloadPassenger
      state.countUnloadReceived = 0
      state.countUnloadPassenger = 0
      expectedUnloadResponses = 0

      if (unloadedCount > 0) {
        val nextTickTime = currentTick + BusUtil.unloadPersonTime(
          numberOfPassengers = unloadedCount,
          numberOfPorts = state.numberOfPorts
        )
        sumoStopTimeSeconds += math.max(0L, nextTickTime - currentTick).toDouble

        BusMetrics.passengersAlighted.labels(state.label).inc(unloadedCount)
        BusMetrics.activePassengers.dec(unloadedCount)
        ActorTrace.trace(getEntityId, currentTick, "bus_passengers_unloaded", // #actor-trace
          s"unloaded=$unloadedCount remaining=${state.people.size} label=${state.label}") // #actor-trace

        report(
          data = Map(
            "event_type" -> "bus_unload_passengers",
            "bus_id" -> getEntityId,
            "passengers_unloaded" -> unloadedCount,
            "remaining_passengers" -> state.people.size,
            "tick" -> currentTick
          ),
          label = "bus_unload_passengers"
        )

        state.status = WaitingUnloadPassenger
        scheduleEvent(nextTickTime)
      } else {
        state.status = WaitingUnloadPassenger
        scheduleEvent(currentTick + 1)
      }
    }
  }

  /** Handle signal state.
    */
  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
    if (state.status != WaitingSignalState) {
      logDebug(
        s"${getEntityId}: Ignoring stale SignalStateData (current status=${state.status}, expected WaitingSignalState). Race condition guard."
      )
      return
    }
    signalStateRetryCounter = 0
    if (data.phase == Red) {
      state.status = WaitingSignal
      signalWaitUntilTick = Some(data.nextTick)
      val waitTicks = math.max(0L, data.nextTick - currentTick)
      if (waitTicks > 0) {
        updateHaltingState(speed = 0.0, deltaSeconds = waitTicks.toDouble)
      }
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

  override protected def microMaxAcceleration: Double = 1.2
  override protected def microMaxDeceleration: Double = 3.5

  override def leavingLink(): Unit = {
    mesoExitTick = None
    signalWaitUntilTick = None
    signalStateRetryCounter = 0
    currentStopNode = state.currentPath.map(_._2)
    state.status = Ready
    super.leavingLink()
  }

  override protected def enterLink(): Unit = {
    restoreRouteIfMissing("enterLink")
    val nextLink = state.movableCurrentPath.orElse(
      state.bestRoute.flatMap { path =>
        if (state.currentPathPosition < path.size) Some(path(state.currentPathPosition))
        else Some(path(0))
      }
    )
    val edgeFound = nextLink.exists { case (linkId, _) =>
      org.interscity.htc.model.hybrid.util.CityMapUtil.edgeLabelsById.contains(linkId)
    }
//    logInfo(s"[BUS-ENTER] ${getEntityId} enterLink: currentPath=${state.movableCurrentPath} nextLink=$nextLink edgeFound=$edgeFound pos=${state.currentPathPosition} movableRoute=${state.movableBestRoute.map(_.size)} bestRoute=${state.bestRoute.map(_.size)} tick=$currentTick")
    super.enterLink()
  }

  override def getNextPath: Option[(String, String)] =
    state.bestRoute match {
      case Some(path) =>
        if (state.currentPathPosition < path.size) {
          val nextPath = path(state.currentPathPosition)
          state.currentPathPosition += 1
          Some(nextPath)
        } else {
          state.currentPathPosition = 1
          Some(path(0))
        }
      case None =>
        None
    }

  /** Check if bus is at a bus stop (for MICRO mode).
    */
  private def checkBusStopAtPosition(position: Double): Unit =
    state.microState.foreach {
      micro =>
        micro.nextBusStop.foreach {
          stopId =>
            busStopProbeLogCount += 1
            if (busStopProbeLogCount % busStopProbeLogEvery == 0L) {
              logDebug(
                s"Bus stop probe[$busStopProbeLogCount]: position=$position, nextStop=$stopId"
              )
              ActorTrace.trace(getEntityId, currentTick, "bus_stop_probe", // #actor-trace)
                s"position=$position nextStop=$stopId label=${state.label}") // #actor-trace
            }
        }
    }

  /** Find next bus stop on route.
    */
  private def findNextBusStop(): Option[String] =
    state.busStops.headOption.map(_._1)

  /** Find bus stop at a given node.
    * @return
    *   Some(busStopId) if a bus stop is mapped to this node, None otherwise.
    */
  private def findBusStopAtNode(nodeId: String): Option[String] =
    state.busStops.find {
      case (_, stopNodeId) => stopNodeId == nodeId
    }.map(_._1)

  /** Request passengers to unload at the current node. Sends BusRequestUnloadPassengerData to each
    * passenger on board. If no passengers, proceeds directly to loading.
    */
  private def requestUnloadPeopleData(): Unit = {
    if (state.people.isEmpty) {
      requestLoadPassenger()
      return
    }

    state.status = WaitingUnloadPassenger
    expectedUnloadResponses = state.people.size
    state.countUnloadReceived = 0
    state.countUnloadPassenger = 0

    val nodeId = currentStopNode.getOrElse("unknown")
    state.people.foreach {
      case (_, person) =>
        sendMessageTo(
          entityId = person.id,
          shardId = person.classType,
          data = BusRequestUnloadPassengerData(
            nodeId = nodeId,
            nodeRef = self
          ),
          eventType = "RequestUnloadPassenger"
        )
    }
    // Same race-condition fix as requestLoadPassenger: use Some(T+1) instead of None
    // to prevent a late FinishEvent from clearing all schedules on arrival after the next
    // processTick has placed the bus in runningEvents.
    onFinishSpontaneous(Some(currentTick + 1))
  }

  /** Request passengers from bus stop at current node. Sends BusRequestPassengerData to the BusStop
    * actor. If no bus stop is found, proceeds to enter next link.
    */
  private def requestLoadPassenger(): Unit = {
    val nodeId = currentStopNode.getOrElse("unknown")
    findBusStopAtNode(nodeId) match {
      case Some(busStopId) =>
        state.status = WaitingLoadPassenger
        sendMessageTo(
          entityId = busStopId,
          shardId = "hybrid.actor.BusStop",
          data = BusRequestPassengerData(
            label = state.label,
            availableSpace = state.capacity - state.people.size
          ),
          eventType = "RequestPassenger"
        )
        onFinishSpontaneous(Some(currentTick + 1))
      case None =>
        currentStopNode = None
        state.status = Ready
        enterLink()
    }
  }

  /** Get current link length.
    */
  private def getCurrentLinkLength: Double =
    currentLinkId.flatMap {
      linkId =>
        org.interscity.htc.model.hybrid.util.CityMapUtil.edgeLabelsById.get(linkId).map(_.length)
    }.getOrElse(1000.0)

  private def finishJourney(reason: String, finalNode: String): Unit = {
    if (journeyFinishedReported) return
    journeyFinishedReported = true
    val vehicleType = getClass.getSimpleName
    MovableMetrics.journeysCompleted.labels(vehicleType).inc()
    MovableMetrics.journeyDistanceMeters.labels(vehicleType).observe(state.distance)
    if (state.destination == finalNode) {
      MovableMetrics.journeySuccesses.labels(vehicleType).inc()
    } else {
      MovableMetrics.journeyFailures.labels(vehicleType, reason).inc()
    }
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
        .orElse(state.currentPath.map(_._2))
        .getOrElse("unknown")
      finishJourney("actor_destructed_before_completion", fallbackNode)
    }
    if (state != null) {
      state.movableBestRoute = None
      state.movableCurrentPath = None
      state.microState = None
      state.people.clear()
    }
  }
}

/** Bus companion object.
  */
object Bus {
  def apply(properties: Properties): Bus =
    new Bus(properties)
}
