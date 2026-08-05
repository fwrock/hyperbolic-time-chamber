package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.types.Tick

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.hybrid.entity.event.data.bus.{ BusLoadPassengerData, BusRequestPassengerData, BusRequestUnloadPassengerData, BusUnloadPassengerData }
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.node.LinkAccessData
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
import model.hybrid.support.bus.{BusJourneyReporter, BusLinkHandler, BusMicroHandler, BusSignalHandler, BusStopHandler}

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

  /** How many MICRO sub-tick updates to skip between live position reports (mirrored to the
    * `kafka` reporter for htc-play). 0 disables live per-sub-tick position reporting entirely —
    * publishing every sub-tick unconditionally would be an order of magnitude more volume than
    * the MESO/walking enter/leave events. See project memory on htc-replay live mode.
    */
  private lazy val microUpdateReportEvery: Int =
    sys.env
      .get("HTC_MICRO_UPDATE_REPORT_EVERY")
      .flatMap(v => scala.util.Try(v.toInt).toOption)
      .orElse(scala.util.Try(config.getInt("htc.report-manager.kafka.micro-update-report-every")).toOption)
      .filter(_ >= 0)
      .getOrElse(0)

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
  // See Car.scala's signalWaitNeedsReverify for what this flags and why.
  private var signalWaitNeedsReverify: Boolean = false

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

  private lazy val journeyReporter = new BusJourneyReporter(
    reportFn      = (data, label) => report(data = data, label = label),
    entityIdFn    = () => getEntityId,
    currentTickFn = () => currentTick
  )

  private lazy val microHandler = new BusMicroHandler(
    reportFn               = (data, label) => reportMirrored(data = data, label = label),
    entityIdFn             = () => getEntityId,
    currentTickFn          = () => currentTick,
    journeyReporter        = journeyReporter,
    onFinishSpontaneousFn  = nextTick => onFinishSpontaneous(nextTick),
    logDebugFn             = msg => logDebug(msg),
    setCurrentLinkIdFn     = id => currentLinkId = id,
    setLinkEntryTickFn     = tick => linkEntryTick = tick,
    getLinkEntryTickFn     = () => linkEntryTick,
    setCurrentStopNodeFn   = node => currentStopNode = node,
    getCurrentLinkLengthFn = () => stopHandler.getCurrentLinkLength,
    findNextBusStopFn      = () => stopHandler.findNextBusStop(state),
    checkBusStopAtPositionFn = pos => stopHandler.checkBusStopAtPosition(pos, state),
    microUpdateLogEvery    = microUpdateLogEvery,
    getCurrentLinkIdFn     = () => currentLinkId,
    microUpdateReportEvery = microUpdateReportEvery
  )

  private lazy val linkHandler = new BusLinkHandler(
    reportFn             = (data, label) => reportMirrored(data = data, label = label),
    entityIdFn           = () => getEntityId,
    currentTickFn        = () => currentTick,
    journeyReporter      = journeyReporter,
    onFinishSpontaneousFn = nextTick => onFinishSpontaneous(nextTick),
    onFinishDestructFn   = () => onFinishSpontaneous(None, destruct = true),
    finishJourneyFn      = (reason, node) => finishJourney(reason, node),
    setMesoExitTickFn    = tick => mesoExitTick = tick,
    restoreRouteIfMissingFn = ctx => restoreRouteIfMissing(ctx)
  )

  private lazy val signalHandler = new BusSignalHandler(
    reportFn               = (data, label) => report(data = data, label = label),
    entityIdFn             = () => getEntityId,
    currentTickFn          = () => currentTick,
    journeyReporter        = journeyReporter,
    onFinishSpontaneousFn  = nextTick => onFinishSpontaneous(nextTick),
    scheduleEventFn        = tick => scheduleEvent(tick),
    onFinishDestructFn     = () => onFinishSpontaneous(None, destruct = true),
    leavingLinkFn          = () => leavingLink(),
    finishJourneyFn        = (reason, node) => finishJourney(reason, node),
    getCurrentNodeFn       = () => getCurrentNode,
    getNextLinkFn          = () => getNextLink,
    sendMessageFn          = (id, shard, data, evType) => sendMessageTo(entityId = id, shardId = shard, data = data, eventType = evType),
    logWarnFn              = msg => logWarn(msg),
    logDebugFn             = msg => logDebug(msg),
    setSignalWaitUntilTickFn = tick => signalWaitUntilTick = tick,
    setSignalWaitNeedsReverifyFn = v => signalWaitNeedsReverify = v,
    restoreRouteIfMissingFn = ctx => restoreRouteIfMissing(ctx)
  )

  private lazy val stopHandler = new BusStopHandler(
    reportFn             = (data, label) => report(data = data, label = label),
    entityIdFn           = () => getEntityId,
    currentTickFn        = () => currentTick,
    journeyReporter      = journeyReporter,
    sendMessageFn        = (id, shard, data, evType) => sendMessageTo(entityId = id, shardId = shard, data = data, eventType = evType),
    onFinishSpontaneousFn = nextTick => onFinishSpontaneous(nextTick),
    deferFinishSpontaneousFn = () => deferFinishSpontaneous(),
    enterLinkFn          = () => enterLink(),
    selfRefFn            = () => self,
    getCurrentStopNodeFn = () => currentStopNode,
    setCurrentStopNodeFn = node => currentStopNode = node,
    logDebugFn           = msg => logDebug(msg),
    getCurrentLinkIdFn   = () => currentLinkId,
    busStopProbeLogEvery = busStopProbeLogEvery
  )

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
        // ActorTrace.trace(getEntityId, currentTick, "bus_journey_started", // #actor-trace
        //   s"origin=${state.origin} destination=${state.destination} route=${state.bestRoute.map(_.size).getOrElse(0)} label=${state.label}") // #actor-trace
        enterLink()

      case Ready =>
        val nodeId = currentStopNode.orNull
        if (nodeId != null && findBusStopAtNode(nodeId).isDefined) {
          stopArrivalCount += 1
          // ActorTrace.trace(getEntityId, currentTick, "bus_stop_arrived", // #actor-trace
          //   s"node=$nodeId stop=${findBusStopAtNode(nodeId).getOrElse("none")} passengers=${state.people.size} label=${state.label}") // #actor-trace
          if (stopArrivalCount % 100 == 0 || stopArrivalCount <= 5) {
//            logInfo(s"[BUS-CYCLE] ${getEntityId} stop #$stopArrivalCount at $nodeId tick=$currentTick")
          }
          requestUnloadPeopleData()
        } else {
          currentStopNode = None
          // If movableStatus is already Waiting, enterLink() was already called and we're
          // waiting on the Link's LinkInfoData reply to flip state.status away from Ready —
          // re-calling enterLink() here would resend a duplicate EnterLinkData for a link
          // this bus is already registered on. Just poll again next tick instead.
          if (state.movableStatus == Waiting) {
            onFinishSpontaneous(Some(currentTick + 1))
          } else {
            enterLink()
          }
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
            if (signalWaitNeedsReverify) {
              signalWaitNeedsReverify = false
              requestSignalState()
            } else {
              leavingLink()
            }
        }

      case WaitingSignalState =>
        // Should never actually fire — requestSignalState() no longer self-schedules
        // after sending; only handleLinkAccess (the Node's reply) resolves this status.
        // Do not resend the request if reached anyway (see Car.scala for rationale).
        logWarn(s"$getEntityId: unexpected actSpontaneous while WaitingSignalState at tick=$currentTick")
        onFinishSpontaneous(Some(currentTick + 1))

      case WaitingCapacity =>
        // Should never actually fire, same reasoning as WaitingSignalState.
        logWarn(s"$getEntityId: unexpected actSpontaneous while WaitingCapacity at tick=$currentTick")
        onFinishSpontaneous(Some(currentTick + 1))

      case WaitingLoadPassenger =>
//        logInfo(s"[BUS-CYCLE] ${getEntityId} WaitingLoadPassenger->enterLink at tick=$currentTick stopCount=$stopArrivalCount")
        currentStopNode = None
        // Same re-entry race as the Ready case above: guard against resending EnterLinkData
        // if enterLink() was already called and we're still waiting on the Link's reply.
        if (state.movableStatus == Waiting) {
          onFinishSpontaneous(Some(currentTick + 1))
        } else {
          enterLink()
        }

      case WaitingUnloadPassenger =>
        requestLoadPassenger()

      case _ =>
        super.actSpontaneous(event)
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: LinkAccessData         => handleLinkAccess(event, d)
      case d: MicroEnterLinkData     => handleMicroEnterLink(event, d)
      case d: MicroUpdateData        => handleMicroUpdate(event, d)
      case d: MicroLeaveLinkData     => handleMicroLeaveLink(event, d)
      case d: BusLoadPassengerData   => handleBusLoadPeople(event, d)
      case d: BusUnloadPassengerData => handleUnloadPassenger(event, d)
      case _                         => super.actInteractWith(event)
    }

  /** Request signal state from node before leaving link.
    */
  private def requestSignalState(): Unit = signalHandler.requestSignalState(state)

  /** Handle entering MICRO link.
    */
  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit =
    microHandler.handleMicroEnterLink(data, state)

  /** Handle microscopic update.
    */
  private def handleMicroUpdate(event: ActorInteractionEvent, data: MicroUpdateData): Unit =
    microHandler.handleMicroUpdate(data, state)

  /** Handle leaving MICRO link.
    */
  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit =
    microHandler.handleMicroLeaveLink(data, state)

  /** Handle entering MESO link.
    */
  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = linkHandler.handleEnterLink(event.actorRefId, data, state)

  /** Handle leaving MESO link.
    */
  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = linkHandler.handleLeaveLink(event.actorRefId, data, state)

  /** Handle passenger loading.
    */
  private def handleBusLoadPeople(event: ActorInteractionEvent, data: BusLoadPassengerData): Unit =
    stopHandler.handleBusLoadPeople(data, state)

  /** Handle passenger unloading.
    */
  private def handleUnloadPassenger(
    event: ActorInteractionEvent,
    data: BusUnloadPassengerData
  ): Unit = stopHandler.handleUnloadPassenger(data, event.actorRefId, state)

  /** Handle signal state.
    */
  private def handleLinkAccess(event: ActorInteractionEvent, data: LinkAccessData): Unit =
    signalHandler.handleLinkAccess(data, state)

  override protected def microMaxAcceleration: Double = 1.2
  override protected def microMaxDeceleration: Double = 3.5

  override def leavingLink(): Unit = {
    mesoExitTick = None
    signalWaitUntilTick = None
    signalWaitNeedsReverify = false
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
  private def checkBusStopAtPosition(position: Double): Unit = stopHandler.checkBusStopAtPosition(position, state)
  private def findNextBusStop(): Option[String] = stopHandler.findNextBusStop(state)
  private def findBusStopAtNode(nodeId: String): Option[String] = stopHandler.findBusStopAtNode(nodeId, state)
  private def requestUnloadPeopleData(): Unit = stopHandler.requestUnloadPeopleData(state)
  private def requestLoadPassenger(): Unit = stopHandler.requestLoadPassenger(state)
  private def getCurrentLinkLength: Double = stopHandler.getCurrentLinkLength

  private def finishJourney(reason: String, finalNode: String): Unit =
    journeyReporter.finishJourney(reason, finalNode, state)

  override def onDestruct(event: DestructEvent): Unit = {
    if (state != null) signalHandler.cancelPendingCapacityRequest(state)
    if (state != null && state.status != Finished) {
      val fallbackNode = Option(getCurrentNode)
        .orElse(state.currentPath.map(_._2))
        .getOrElse("unknown")
      journeyReporter.finishJourney("actor_destructed_before_completion", fallbackNode, state)
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
