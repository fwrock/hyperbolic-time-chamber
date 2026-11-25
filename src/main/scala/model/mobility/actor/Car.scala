package org.interscity.htc
package model.mobility.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick
import core.actor.manager.loadbalance.migration.MigrationSnapshot

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData

import org.interscity.htc.model.mobility.entity.event.node.LinkAccessData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.*

import org.interscity.htc.model.mobility.util.GPSUtil
import org.interscity.htc.model.mobility.entity.state.{CarState, DriverAttributes}
import org.interscity.htc.model.mobility.entity.event.data.*
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics

import core.util.StringPool
import model.mobility.support.car.{CarEmissionHandler, CarJourneyReporter, CarLinkHandler, CarMicroHandler, CarSignalHandler}

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
      origin      = s.origin,
      destination = s.destination,
      journey = s.journey.copy(
        sumoDepartLane = s.journey.sumoDepartLane.map(StringPool.intern),
        sumoArrivalLane = s.journey.sumoArrivalLane.map(StringPool.intern)
      )
    )
    copied.movableStatus             = s.movableStatus
    copied.movableBestRoute          = s.movableBestRoute
    copied.movableCurrentPath        = s.movableCurrentPath
    copied.movableCurrentNode        = s.movableCurrentNode
    copied.movableBestCost           = s.movableBestCost
    copied.movableReachedDestination = s.movableReachedDestination
    copied.movableLinkExitReported   = s.movableLinkExitReported
    copied.movableMicroSchedulingDelegated = s.movableMicroSchedulingDelegated
    copied.movableMicroLeaveHandledByLink = s.movableMicroLeaveHandledByLink
    copied.movablePendingRouteQuery  = s.movablePendingRouteQuery
    copied.movablePendingJunctionPath = s.movablePendingJunctionPath
    copied.movableCurrentPathIsJunction = s.movableCurrentPathIsJunction
    copied
  }

  /** Adds this class's own link/signal-wait bookkeeping on top of `PrivateVehicle`'s
    * `captureMigrationFields` (`ownerPersonRef` etc.). Found uncaptured by `docs/
    * TIME_WARP_DESIGN.md`'s checkpoint-completeness audit (2026-08-07): `currentLinkId`/
    * `currentLinkLength`/`linkEntryTick`/`mesoExitTick`/`signalWaitUntilTick`/
    * `signalWaitNeedsReverify` are all actor-local `var`s driving branches in `actSpontaneous`
    * (`Moving`'s `mesoExitTick` check, `WaitingSignal`'s `signalWaitUntilTick`/
    * `signalWaitNeedsReverify` check) — a `RollbackHistoryHandler` restore (same primitive as
    * shard migration) that skips them leaves `state.status` correctly restored but these fields at
    * whatever live execution left them, taking a different branch on replay than the original run
    * did. `Motorcycle`/`Bicycle` share the identical set of fields minus `currentLinkLength`; see
    * `MigrationSnapshot`'s `vehicle*` fields, which are named generically for that reuse, not
    * `Car`-specific.
    */
  private def captureCarMigrationFields(base: MigrationSnapshot): MigrationSnapshot =
    base.copy(
      vehicleCurrentLinkId = currentLinkId.getOrElse(0L),
      vehicleCurrentLinkLength = currentLinkLength,
      vehicleLinkEntryTick = linkEntryTick.getOrElse(Long.MinValue),
      vehicleMesoExitTick = mesoExitTick.getOrElse(Long.MinValue),
      vehicleSignalWaitUntilTick = signalWaitUntilTick.getOrElse(Long.MinValue),
      vehicleSignalWaitNeedsReverify = signalWaitNeedsReverify
    )

  /** Restores what [[captureCarMigrationFields]] captured. */
  private def restoreCarMigrationFields(snapshot: MigrationSnapshot): Unit = {
    currentLinkId = if (snapshot.vehicleCurrentLinkId != 0L) Some(snapshot.vehicleCurrentLinkId) else None
    currentLinkLength = snapshot.vehicleCurrentLinkLength
    linkEntryTick = if (snapshot.vehicleLinkEntryTick != Long.MinValue) Some(snapshot.vehicleLinkEntryTick) else None
    mesoExitTick = if (snapshot.vehicleMesoExitTick != Long.MinValue) Some(snapshot.vehicleMesoExitTick) else None
    signalWaitUntilTick =
      if (snapshot.vehicleSignalWaitUntilTick != Long.MinValue) Some(snapshot.vehicleSignalWaitUntilTick) else None
    signalWaitNeedsReverify = snapshot.vehicleSignalWaitNeedsReverify
  }

  override protected def buildMigrationSnapshot(): MigrationSnapshot =
    captureCarMigrationFields(captureMigrationFields(super.buildMigrationSnapshot()))

  override protected def applyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = {
    super.applyMigrationSnapshot(snapshot)
    restoreMigrationFields(snapshot)
    restoreCarMigrationFields(snapshot)
  }

  // protected, not private: lets CarLinkWaitMigrationSnapshotSpec drive these directly rather than
  // reverse-engineering LinkInfoData/LinkAccessData payloads just to exercise the capture/restore
  // round trip -- same rationale as this class's other test-only protected accessors.
  protected var currentLinkId: Option[Long] = None
  protected var currentLinkLength: Double = 0.0
  protected var linkEntryTick: Option[Tick] = None
  protected var mesoExitTick: Option[Tick] = None

  private lazy val simulationEndTick: Tick =
    model.mobility.util.VehicleSimulationConfig.simulationEndTick

  protected var signalWaitUntilTick: Option[Tick] = None
  protected var signalWaitNeedsReverify: Boolean = false

  private lazy val journeyReporter = new CarJourneyReporter(
    reportFn        = (data, label) => report(data = data, label = label),
    entityIdFn      = () => getEntityId,
    currentTickFn   = () => currentTick,
    tripOriginFn    = () => getTripOrigin,
    tripDestFn      = () => getTripDestination,
    tripStartTickFn = () => getTripStartTick,
    driverAttrsFn   = () => getDriverAttributes,
    journeyStateFn  = () => state.journey,
    tripStartDistanceFn = () => getTripStartDistance
  )

  private lazy val signalHandler = new CarSignalHandler(
    reportFn                    = (data, label) => report(data = data, label = label),
    entityIdFn                  = () => getEntityId,
    currentTickFn               = () => currentTick,
    journeyReporter             = journeyReporter,
    onFinishSpontaneousFn       = nextTick => onFinishSpontaneous(nextTick),
    scheduleEventFn             = tick => scheduleSignalWake(tick, signalWaitNeedsReverify),
    leavingLinkFn               = () => leavingLink(),
    logWarnFn                   = msg => logWarn(msg),
    logStaleEventDebugFn        = msg => logStaleEventDebug(msg),
    sendMessageFn               = (id, shard, data, evType) => sendMessageTo(entityId = id, shardId = shard, data = data, eventType = evType),
    getCurrentNodeFn            = () => getCurrentNode,
    getNextLinkFn               = () => getNextLink,
    setSignalWaitUntilTickFn    = tick => signalWaitUntilTick = tick,
    setSignalWaitNeedsReverifyFn = v => signalWaitNeedsReverify = v,
    onSignalWaitFn              = waitTicks => emissionHandler.onSignalWait(waitTicks, state.journey.secondsPerTick),
    isTimeWarpFn                = () => requiresReplyCorrelation,
    registerAwaitedReplyFn      = (key, kind) => registerAwaitedReply(key, kind),
    requestMessageFn = Some((eid, shard, data, event, key, kind) =>
      requestInteraction(eid, shard, data, event, key, kind)),
    isAwaitingReplyFn           = (key, kind, seq) => isAwaitingReply(key, kind, seq),
    consumeAwaitedReplyFn       = key => consumeAwaitedReply(key)
  )

  private lazy val linkHandler = new CarLinkHandler(
    reportFn              = (data, label) => reportMirrored(data = data, label = label),
    entityIdFn            = () => getEntityId,
    currentTickFn         = () => currentTick,
    journeyReporter       = journeyReporter,
    onFinishSpontaneousFn = nextTick => finishOrContinueSpontaneous(nextTick),
    finishAndCleanupFn    = (reason, node) => finishAndCleanup(reason, node),
    logStaleEventDebugFn  = msg => logStaleEventDebug(msg),
    setCurrentLinkIdFn    = id => currentLinkId = id,
    setCurrentLinkLengthFn = len => currentLinkLength = len,
    setLinkEntryTickFn    = tick => linkEntryTick = tick,
    getLinkEntryTickFn    = () => linkEntryTick,
    setMesoExitTickFn     = tick => mesoExitTick = tick,
    getTripDestinationFn  = () => getTripDestination,
    isTimeWarpFn          = () => requiresReplyCorrelation,
    isAwaitingReplyFn     = (key, kind, seq) => isAwaitingReply(key, kind, seq),
    consumeAwaitedReplyFn = key => consumeAwaitedReply(key)
  )

  private lazy val microHandler = new CarMicroHandler(
    reportFn              = (data, label) => reportMirrored(data = data, label = label),
    entityIdFn            = () => getEntityId,
    currentTickFn         = () => currentTick,
    simulationEndTickFn   = () => simulationEndTick,
    extendSimulationFn    = () => model.mobility.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd,
    journeyReporter       = journeyReporter,
    requestSignalStateFn  = () => requestSignalState(),
    onFinishSpontaneousFn = nextTick => onFinishSpontaneous(nextTick),
    finishAndCleanupFn    = (reason, node) => finishAndCleanup(reason, node),
    onFinishPrivateVehicleFn = node => onFinishPrivateVehicle(node),
    selfDestructFn        = () => selfDestruct(),
    finishJourneyFn       = (reason, node) => finishJourney(reason, node),
    logWarnFn             = msg => logWarn(msg),
    logDebugFn            = msg => logDebug(msg),
    setCurrentLinkIdFn    = id => currentLinkId = id,
    setCurrentLinkLengthFn = len => currentLinkLength = len,
    setLinkEntryTickFn    = tick => linkEntryTick = tick,
    getLinkEntryTickFn    = () => linkEntryTick,
    getCurrentLinkIdFn    = () => currentLinkId,
    microUpdateReportEvery = microUpdateReportEvery,
    runtimeOwnsHorizonFn = () => usesAccountedRuntime,
    onDelegatedAccessFn = access => {
      state.status = model.mobility.entity.state.enumeration.MovableStatusEnum.WaitingSignalState
      signalHandler.handleLinkAccess(access, state)
    }
  )

  private lazy val emissionHandler = new CarEmissionHandler(
    microStrategy = model.mobility.util.VehicleSimulationConfig.microEmissionStrategy,
    mesoStrategy  = model.mobility.util.VehicleSimulationConfig.mesoEmissionStrategy,
    reportFn      = (data, label) => report(data = data, label = label),
    entityIdFn    = () => getEntityId,
    currentTickFn = () => currentTick
  )

  private lazy val staleEventLogEvery: Int =
    scala.util.Try(config.getInt("htc.car.stale-event-log-every")).toOption
      .filter(_ > 0)
      .getOrElse(100)

  private var staleEventLogCount: Long = 0L

  /** How many MICRO sub-tick updates to skip between live position reports (mirrored to the
    * `kafka` reporter for htc-play). 0 disables live per-sub-tick position reporting entirely —
    * publishing every sub-tick unconditionally would be an order of magnitude more volume than
    * the MESO/walking enter/leave events. See project memory on htc-replay live mode.
    */
  private lazy val microUpdateReportEvery: Int =
    scala.util.Try(config.getInt("htc.report-manager.kafka.micro-update-report-every")).toOption
      .filter(_ >= 0)
      .getOrElse(0)

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
    entityId: Long,
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
  override protected def applyPrecomputedRoute(route: List[(Long, Long)]): Unit =
    state.bestRoute = Some(scala.collection.mutable.Queue(route: _*))

  /** Reset all per-trip tracking variables so metrics start fresh for each new trip. Called by
    * PrivateVehicle.handleStartTrip before each activation. Critical for person-centric vehicles
    * that serve multiple trips without being destroyed.
    */
  override protected def resetTripState(): Unit = {
    if (state == null) return
    state.movablePendingJunctionPath = None
    state.movableCurrentPathIsJunction = false
    currentLinkId = None
    currentLinkLength = 0.0
    linkEntryTick = None
    mesoExitTick = None
    journeyReporter.reset()
    emissionHandler.reset()
    signalWaitUntilTick = None
    signalWaitNeedsReverify = false
    state.bestRoute = None
    state.precomputedRoute = None
    state.deactivateMicroMode()
  }

  override protected def onHorizonFinalization(horizon: Tick): Unit =
    if (state.status != Finished && state.status != model.mobility.entity.state.enumeration.MovableStatusEnum.Parked &&
      state.startTick <= horizon)
      model.mobility.support.JourneyHorizonReporter.report("car", getEntityId,
        getTripOrigin.getOrElse(state.origin), getTripDestination.getOrElse(state.destination),
        journeyReporter.sumoDepartTick.getOrElse(-1L), horizon, state.distance - getTripStartDistance,
        Option.when(journeyReporter.sumoDepartTick.nonEmpty)(state.currentPath.map(_._1)).flatten,
        (data, label) => report(data, label),
        if (journeyReporter.sumoDepartTick.nonEmpty) state.journey.secondsPerTick else core.util.SimulationClock.current.secondsPerTick,
        scheduledDeparture=Some(getTripStartTick.getOrElse(state.startTick)))

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null || state.status == Parked) {
      onFinishSpontaneous(None)
      return
    }

    if (
      !usesAccountedRuntime && !model.mobility.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
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
            if (signalWaitNeedsReverify) {
              signalWaitNeedsReverify = false
              requestSignalState()
            } else {
              leavingLink()
            }
        }

      case WaitingSignalState =>
        logWarn(s"$getEntityId: unexpected actSpontaneous while WaitingSignalState at tick=$currentTick")
        onFinishSpontaneous(Some(currentTick + 1))

      case WaitingCapacity =>
        logWarn(s"$getEntityId: unexpected actSpontaneous while WaitingCapacity at tick=$currentTick")
        onFinishSpontaneous(Some(currentTick + 1))

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
      case d: LinkAccessData     => handleLinkAccess(event, d)
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
          Some((item.linkId, item.nodeId))
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

    if (origin == 0L || destination == 0L) {
      val tripOrigin = getTripOrigin.getOrElse(state.origin)
      finishAndCleanup("null_origin_or_destination", tripOrigin)
      return
    }

    if (requestAccountedRoute(origin, destination, Int.MaxValue)) return

    try
      handleCalculatedRouteResult(
        GPSUtil.calcRouteCompact(originId = origin, destinationId = destination, maxExpansions = Int.MaxValue)
      )
    catch {
      case e: Exception =>
        logError(s"Exception during route request: ${e.getMessage}", e)
        finishAndCleanup("exception_during_route_request", origin)
    }
  }

  override protected def handleCalculatedRouteResult(
    result: Option[(Double, mutable.Queue[(Long, Long)])]
  ): Unit = {
    val origin = getTripOrigin.getOrElse(state.origin)
    val destination = getTripDestination.getOrElse(state.destination)
    result match {
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
  }

  private def finishAndCleanup(reason: String, finalNode: Long, wasTeleported: Boolean = false): Unit = {
    signalHandler.cancelPendingCapacityRequest(state)
    finishJourney(reason, finalNode)
    onFinishPrivateVehicle(finalNode, wasTeleported)
    onFinishSpontaneous(None)
    if (!isPersonCentric) selfDestruct()
  }

  private def requestSignalState(): Unit = signalHandler.requestSignalState(state)

  private def handleLinkAccess(event: ActorInteractionEvent, data: LinkAccessData): Unit =
    signalHandler.handleLinkAccess(data, state, eventTick = event.tick, eventSeq = event.seq)

  override protected def mesoOriginAtEnd: Boolean =
    state.journey.sumoDepartTick.isEmpty && state.mesoOriginLinkId.exists(id => state.currentPath.exists(_._1 == id))

  override protected def microEntryVelocity: Double =
    if (state.journey.sumoDepartTick.isEmpty)
      state.microDepartSpeed.getOrElse(journeyReporter.sumoArrivalSpeed)
    else journeyReporter.sumoArrivalSpeed
  override protected def microAcceleration: Option[Double] = state.microAcceleration
  override protected def microMinGap: Option[Double] = state.microMinGap
  override protected def microReactionTime: Option[Double] = state.microReactionTime
  override protected def microMaxSpeed: Option[Double] = state.microMaxSpeed
  override protected def microSpeedFactor: Double = state.microSpeedFactor
  override protected def microDesiredMaxSpeed: Option[Double] = state.microDesiredMaxSpeed
  override protected def microSigma: Double = state.microSigma
  override protected def microRandomSeed: Long = state.microRandomSeed
  override protected def microLc2013: model.mobility.entity.state.model.Lc2013Parameters = state.microLc2013
  override protected def microEntryLane: Option[Int] =
    if (state.movableCurrentPathIsJunction) None
    else if (state.journey.sumoDepartTick.isEmpty) state.microDepartLane.orElse(state.journey.pendingRoadLane)
    else state.journey.pendingRoadLane
  override protected def microDeceleration: Option[Double] = state.microDeceleration
  override protected def microEntryPosition: Double =
    if (state.journey.sumoDepartTick.isEmpty) state.microDepartPosition else 0.0
  override protected def microArrivalPosition: Option[Double] =
    if (!state.movableCurrentPathIsJunction && getNextLink == 0L) state.microArrivalPosition else None
  override protected def microEntryRemainderSeconds: Double =
    state.journey.entryRemainderAt(currentTick)

  override def leavingLink(): Unit = {
    mesoExitTick = None
    signalWaitUntilTick = None
    signalWaitNeedsReverify = false
    state.status = Ready
    val held = holdPreviousLinkUntilNextAdmitted
    super.leavingLink()
    if (!held) state.journey.retainedMicroExitLinkId = None
  }

  override protected def leaveHandledByLink: Boolean = state.journey.leaveHandledByLink

  override protected def leaveAckRequired: Boolean = state.journey.leaveAckRequired || !isDirectConservativeRuntime

  override protected def completeLeaveWithoutReply(linkEntityId: Long): Unit = {
    val entryTick = linkEntryTick
    val length    = currentLinkLength
    linkHandler.leaveWithoutReply(linkEntityId, length, state)
    entryTick.foreach { entry =>
      emissionHandler.onLeaveLink(linkEntityId, length, (currentTick - entry).toDouble)
    }
  }

  override protected def holdPreviousLinkUntilNextAdmitted: Boolean =
    state.journey.retainedMicroExitLinkId.nonEmpty && !state.movableBestRoute.forall(_.isEmpty)

  override protected def onFinish(nodeId: Long): Unit = {
    finishJourney("onFinish_called", nodeId)
    onFinishPrivateVehicle(nodeId)
    if (!isPersonCentric) {
      if (state.destination == nodeId) state.movableReachedDestination = true
      state.movableStatus = Finished
      onFinishSpontaneous(None, destruct = true)
    }
  }

  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit = {
    if (requiresReplyCorrelation && data.requestSeq != 0L &&
      !isAwaitingReply(Movable.enterLinkAwaitKey(event.actorRefId),"EnterLinkData",data.requestSeq)) return
    consumeAwaitedReply(Movable.enterLinkAwaitKey(event.actorRefId))
    // Release the retained footprint only now: the next Link has physically admitted this Car.
    state.journey.retainedMicroExitLinkId.foreach { approach =>
      sendMessageTo(entityId = approach, shardId = "mobility.actor.Link",
        data = model.mobility.entity.event.data.LeaveLinkData(state.actorType, state.size,
          core.enumeration.CreationTypeEnum.LoadBalancedDistributed),
        eventType = model.mobility.entity.state.enumeration.EventTypeEnum.LeaveLink.toString)
    }
    state.journey.retainedMicroExitLinkId = None
    microHandler.handleMicroEnterLink(data, state)
    emissionHandler.onMicroEnterLink(data.linkId, data.microTimeStep)
  }

  private def handleMicroUpdate(event: ActorInteractionEvent, data: MicroUpdateData): Unit = {
    microHandler.handleMicroUpdate(data, state)
    emissionHandler.onMicroUpdate(data.velocity, data.acceleration)
  }

  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    microHandler.handleMicroLeaveLink(data, state)
    emissionHandler.onMicroLeaveLink(data.linkId, data.distanceTraveled, data.travelTime)
  }

  override protected def linkAccessRequest(): Option[model.mobility.entity.event.data.LinkAccessRequest] =
    if (state.bestRoute.forall(_.isEmpty) ||
      state.currentPath.isEmpty) None
    else {
      val nodeId = getCurrentNode
      val linkId = getNextLink
      if (nodeId == 0L || linkId == 0L) None
      else model.mobility.util.CityMapUtil.nodesById.get(nodeId).map { node =>
        model.mobility.entity.event.data.LinkAccessRequest(node.id, node.classType,
          model.mobility.entity.event.data.vehicle.RequestLinkAccessData(
            targetLinkId = linkId,
            signalAlreadyCleared = state.movableLinkExitReported,
            approachLinkId = state.currentPath.map(_._1).getOrElse(0L),
            approachLane = state.journey.lastMicroLane,
            followingLinkId = state.bestRoute.flatMap(_.iterator.drop(1).nextOption()).map(_._1).getOrElse(0L)))
      }
    }

  override protected def delegatedEntry(): Option[model.mobility.entity.event.data.DelegatedEntry] =
    if (model.mobility.util.VehicleSimulationConfig.linkOwnedExit && supportsDelegatedScheduling)
      Some(model.mobility.entity.event.data.DelegatedEntry(
        wake = core.runtime.DelegatedWake(getClass.getName, getEntityId.toLong, 0L),
        secondsPerTick = state.journey.secondsPerTick,
        carryRounding = model.mobility.util.VehicleSimulationConfig.mesoCarryRounding,
        roundingCredit = state.journey.mesoRoundingCredit,
        lastExitTick = state.journey.mesoLastExitTick,
        deferReply = true))
    else if (model.mobility.util.VehicleSimulationConfig.delegatedEntryScheduling && supportsDelegatedScheduling)
      Some(model.mobility.entity.event.data.DelegatedEntry(
        wake = delegateWake(),
        secondsPerTick = state.journey.secondsPerTick,
        carryRounding = model.mobility.util.VehicleSimulationConfig.mesoCarryRounding,
        roundingCredit = state.journey.mesoRoundingCredit,
        lastExitTick = state.journey.mesoLastExitTick))
    else None

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    linkHandler.handleEnterLink(event.actorRefId, data, state)
    // The Link held this reply until the exit tick, so the vehicle's wake is this very callback.
    if (data.entryTick >= 0L && state.status == Moving && !state.isMicroMode) {
      val exitReached = mesoExitTick.forall(_ <= currentTick)
      if (exitReached) {
        mesoExitTick = None
        data.access match {
          case Some(access) =>
            state.journey.leaveHandledByLink = data.leaveDone
            state.status = WaitingSignalState
            signalHandler.handleLinkAccess(access, state, eventTick = event.tick, eventSeq = event.seq)
          case None => requestSignalState()
        }
      }
    }
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val entryTick = linkEntryTick
    linkHandler.handleLeaveLink(event.actorRefId, data, state)
    entryTick.foreach { entry =>
      val travelTimeSecs = (currentTick - entry).toDouble
      emissionHandler.onLeaveLink(event.actorRefId, data.linkLength, travelTimeSecs)
    }
  }

  private def finishJourney(reason: String, finalNode: Long): Unit =
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

    signalHandler.cancelPendingCapacityRequest(state)
    // Destroyed after a capacity grant but before the next Link acknowledged admission: the footprint held on the previous Link
    // would never be released. (A WaitingCapacity cancel above already released and cleared it.)
    state.journey.retainedMicroExitLinkId.foreach { approach =>
      sendMessageTo(entityId = approach, shardId = "mobility.actor.Link",
        data = model.mobility.entity.event.data.LeaveLinkData(state.actorType, state.size,
          core.enumeration.CreationTypeEnum.LoadBalancedDistributed),
        eventType = model.mobility.entity.state.enumeration.EventTypeEnum.LeaveLink.toString)
    }
    state.journey.retainedMicroExitLinkId = None

    if (state.status != Finished) {
      val fallbackNode = Option(getCurrentNode)
        .orElse(state.movableCurrentPath.map(_._2))
        .getOrElse(state.origin)
      journeyReporter.finishJourney("actor_destructed_before_completion", fallbackNode, state)
      onFinishPrivateVehicle(fallbackNode)
    }
    state.movableBestRoute = None
    state.movableCurrentPath = None
    state.microState = None
  }
}

object Car {
  def apply(properties: Properties): Car = new Car(properties)
}
