package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick
import core.actor.manager.loadbalance.migration.MigrationSnapshot

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.node.LinkAccessData
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.*
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.{CityMapUtil, GPSUtil}
import org.interscity.htc.model.hybrid.support.bicycle.{
  BicycleJourneyReporter, BicycleLinkHandler, BicycleMicroHandler, BicycleSignalHandler
}
import org.interscity.htc.model.hybrid.entity.state.{BicycleState, DriverAttributes, MicroBicycleState}
import org.interscity.htc.model.hybrid.entity.event.data.*
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.metrics.model.hybrid.{ GPSMetrics, MovableMetrics }
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import core.util.StringPool

/** Bicycle actor - NEW vehicle type for hybrid simulator.
  *
  * NOW A PRIVATE VEHICLE ASSET (Person-Centric Model):
  *   - Passive (Parked) by default
  *   - Activated by Person via StartTrip message
  *   - Configured with person's DriverAttributes
  *   - Reports back with TripCompleted
  *   - Low speeds (typically 15-25 km/h)
  *   - Prefer bike lanes when available
  *   - Can share lanes with cars if necessary
  *   - Lower acceleration and deceleration
  *   - Smaller vehicle size (2m)
  *
  * MESO mode:
  *   - Aggregate bicycle flow
  *   - Simplified speed calculations
  *
  * MICRO mode:
  *   - Detailed positioning with bicycle-specific parameters
  *   - Bike lane preference
  *   - Safety gap considerations (vulnerable user)
  *   - Interactions with cars and other vehicles
  *
  * @param properties
  *   Actor properties
  */
class Bicycle(
  private val properties: Properties
) extends Movable[BicycleState](
      properties = properties
    )
    with PrivateVehicle[BicycleState] {

  override protected def internStateStrings(s: BicycleState): BicycleState = {
    val copied = s.copy(
      origin      = StringPool.intern(s.origin),
      destination = StringPool.intern(s.destination)
    )
    // copy() only replicates BicycleState constructor params; restore MovableState vars
    // that are not in the constructor (otherwise they reset to their defaults).
    copied.movableStatus             = s.movableStatus
    copied.movableBestRoute          = s.movableBestRoute
    copied.movableCurrentPath        = s.movableCurrentPath
    copied.movableCurrentNode        = s.movableCurrentNode
    copied.movableBestCost           = s.movableBestCost
    copied.movableReachedDestination = s.movableReachedDestination
    copied
  }

  /** Wires PrivateVehicle's reply-linkage fields (ownerPersonRef, tripOrigin, etc. — see
    * docs/KNOWN_GAPS.md "Shard Migration Silently Drops Actor-Local Reply State") into the
    * migration snapshot. Must live here, not in the trait: the trait's self-type doesn't let it
    * call `super.buildMigrationSnapshot()` (see PrivateVehicle.captureMigrationFields).
    */
  override protected def buildMigrationSnapshot(): MigrationSnapshot =
    captureMigrationFields(super.buildMigrationSnapshot())

  override protected def applyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = {
    super.applyMigrationSnapshot(snapshot)
    restoreMigrationFields(snapshot)
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

  /** Maximum simulation end tick - vehicles must finish by this tick. */
  private lazy val simulationEndTick: Tick =
    model.hybrid.util.VehicleSimulationConfig.simulationEndTick

  private var signalWaitUntilTick: Option[Tick] = None
  // See Car.scala's signalWaitNeedsReverify for what this flags and why.
  private var signalWaitNeedsReverify: Boolean = false

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

  private lazy val journeyReporter = new BicycleJourneyReporter(
    reportFn        = (data, label) => report(data = data, label = label),
    entityIdFn      = () => getEntityId,
    currentTickFn   = () => currentTick,
    tripOriginFn    = () => getTripOrigin,
    tripDestFn      = () => getTripDestination,
    tripStartTickFn = () => getTripStartTick,
    driverAttrsFn   = () => getDriverAttributes
  )

  private lazy val microHandler = new BicycleMicroHandler(
    reportFn                 = (data, label) => reportMirrored(data = data, label = label),
    entityIdFn               = () => getEntityId,
    currentTickFn            = () => currentTick,
    journeyReporter          = journeyReporter,
    requestSignalStateFn     = () => requestSignalState(),
    onFinishSpontaneousFn    = tick => onFinishSpontaneous(tick),
    onFinishPrivateVehicleFn = node => onFinishPrivateVehicle(node),
    selfDestructFn           = () => selfDestruct(),
    isPersonCentricFn        = () => isPersonCentric,
    finishJourneyFn          = (reason, node) => journeyReporter.finishJourney(reason, node, state),
    logDebugFn               = msg => logDebug(msg),
    setCurrentLinkIdFn       = id => currentLinkId = id,
    setLinkEntryTickFn       = t => linkEntryTick = t,
    getLinkEntryTickFn       = () => linkEntryTick,
    getCurrentLinkIdFn       = () => currentLinkId,
    microUpdateReportEvery   = microUpdateReportEvery
  )

  private lazy val linkHandler = new BicycleLinkHandler(
    reportFn                 = (data, label) => reportMirrored(data = data, label = label),
    entityIdFn               = () => getEntityId,
    currentTickFn            = () => currentTick,
    journeyReporter          = journeyReporter,
    onFinishSpontaneousFn    = tick => onFinishSpontaneous(tick),
    onFinishPrivateVehicleFn = node => onFinishPrivateVehicle(node),
    selfDestructFn           = () => selfDestruct(),
    isPersonCentricFn        = () => isPersonCentric,
    finishJourneyFn          = (reason, node) => journeyReporter.finishJourney(reason, node, state),
    setMesoExitTickFn        = t => mesoExitTick = t,
    getTripDestinationFn     = () => getTripDestination,
    logDebugFn               = msg => logDebug(msg)
  )

  private lazy val signalHandler = new BicycleSignalHandler(
    reportFn                     = (data, label) => report(data = data, label = label),
    entityIdFn                   = () => getEntityId,
    currentTickFn                = () => currentTick,
    journeyReporter              = journeyReporter,
    onFinishSpontaneousFn        = tick => onFinishSpontaneous(tick),
    scheduleEventFn              = tick => scheduleEvent(tick),
    leavingLinkFn                = () => leavingLink(),
    selfDestructFn               = () => selfDestruct(),
    isPersonCentricFn            = () => isPersonCentric,
    logDebugFn                   = msg => logDebug(msg),
    sendMessageFn                = (id, shard, d, et) => sendMessageTo(entityId = id, shardId = shard, data = d, eventType = et),
    getCurrentNodeFn             = () => getCurrentNode,
    getNextLinkFn                = () => getNextLink,
    getTripDestinationFn         = () => getTripDestination,
    finishJourneyFn              = (reason, node) => journeyReporter.finishJourney(reason, node, state),
    onFinishPrivateVehicleFn     = node => onFinishPrivateVehicle(node),
    setSignalWaitUntilTickFn     = t => signalWaitUntilTick = t,
    setSignalWaitNeedsReverifyFn = v => signalWaitNeedsReverify = v
  )

  override protected def getVehicleStatus
    : org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum = state.status
  override protected def setVehicleStatus(
    status: org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum
  ): Unit =
    state.status = status
  override protected def getActorCurrentTick: Tick = currentTick
  override protected def getActorShardId: String = getShardId
  override protected def getActorEntityId: String = getEntityId
  override protected def scheduleNextTick(nextTick: Option[Tick]): Unit = onFinishSpontaneous(
    nextTick
  )
  override protected def selfDestructVehicle(): Unit                     = selfDestruct()
  override protected def isVehicleStateNull: Boolean                     = state == null
  override protected def getCurrentDistance: Double = state.distance
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

  /** Reset all per-trip tracking variables so metrics start fresh for each new trip. Called by
    * PrivateVehicle.handleStartTrip before each activation.
    */
  /** Pre-load route pre-computed by ModeChoiceStrategy so requestRoute() skips a second A*.
    */
  override protected def applyPrecomputedRoute(route: List[(String, String)]): Unit =
    state.bestRoute = Some(scala.collection.mutable.Queue(route: _*))

  override protected def resetTripState(): Unit = {
    if (state == null) return
    currentLinkId = None
    linkEntryTick = None
    mesoExitTick = None
    signalWaitUntilTick = None
    signalWaitNeedsReverify = false
    journeyReporter.reset()
    state.bestRoute = None
    state.deactivateMicroMode()
  }

  // ===== End Accessor Methods =====

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null) {
      logWarn(
        "Bicycle state is null, cannot process spontaneous event — sending FinishEvent to unblock TimeManager"
      )
      onFinishSpontaneous(None)
      return
    }

    if (state.status == Parked) {
      onFinishSpontaneous(None)
      return
    }

    if (
      !model.hybrid.util.VehicleSimulationConfig.extendSimulationIfPendingEventsAfterEnd
      && currentTick >= simulationEndTick && state.status != Finished
    ) {
      logDebug(
        s"Bicycle ${getEntityId} exceeded simulation end time ($simulationEndTick) at tick $currentTick, force-finishing."
      )
      val finalNode = Option(getCurrentNode).getOrElse(state.destination)
      finishJourney("simulation_time_exceeded", finalNode)
      onFinishPrivateVehicle(finalNode)
      onFinishSpontaneous(None)
      selfDestruct()
      return
    }

    logInfo(s"[BICYCLE] actSpontaneous tick=$currentTick id=$getEntityId status=${state.status} isMicro=${state.isMicroMode}")
    state.status match {
      case Start =>
        requestRoute()

      case Ready =>
        // If movableStatus is already Waiting, enterLink() was already called and we're
        // waiting on the Link's LinkInfoData reply to flip state.status away from Ready —
        // re-calling enterLink() here would resend a duplicate EnterLinkData for a link
        // this bicycle is already registered on. Just poll again next tick instead.
        if (state.movableStatus == Waiting) {
          onFinishSpontaneous(Some(currentTick + 1))
        } else {
          enterLink()
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

      case Moving =>
        if (state.isMicroMode) {
          onFinishSpontaneous(None)
        } else {
          mesoExitTick match {
            case Some(exitTick) if currentTick < exitTick =>
              onFinishSpontaneous(Some(exitTick))
            case _ =>
              mesoExitTick = None
              requestSignalState()
          }
        }

      case WaitingSignalState =>
        logWarn(s"Bicycle ${getEntityId}: unexpected actSpontaneous while WaitingSignalState at tick=$currentTick")
        onFinishSpontaneous(Some(currentTick + 1))

      case WaitingCapacity =>
        logWarn(s"Bicycle ${getEntityId}: unexpected actSpontaneous while WaitingCapacity at tick=$currentTick")
        onFinishSpontaneous(Some(currentTick + 1))

      case Finished =>
        onFinishSpontaneous()

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
          logDebug(
            s"${getEntityId} received stale MICRO event with null state, discarding: ${event.eventType}"
          )
        case _: LinkInfoData =>
          logDebug(
            s"${getEntityId} received stale MESO link event with null state, discarding: ${event.eventType}"
          )
        case _ =>
          logWarn(
            s"${getEntityId} received interaction event with null state, discarding: ${event.eventType}"
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

  private def requestSignalState(): Unit = signalHandler.requestSignalState(state)

  private def handleLinkAccess(event: ActorInteractionEvent, data: LinkAccessData): Unit =
    signalHandler.handleLinkAccess(data, state)

  override protected def microMaxAcceleration: Double = 1.0
  override protected def microMaxDeceleration: Double = 3.0

  override def leavingLink(): Unit = {
    mesoExitTick = None
    signalWaitUntilTick = None
    signalWaitNeedsReverify = false
    state.status = Ready
    super.leavingLink()
  }

  override def requestRoute(): Unit = {
    if (state.status == Finished) {
      return
    }
    // Skip GPS if route was pre-loaded by TravelTimeModeChoiceStrategy (avoids double A*)
    if (state.bestRoute.exists(_.nonEmpty)) {
      val preRoute    = state.bestRoute.get
      val origin      = getTripOrigin.getOrElse(state.origin)
      val destination = getTripDestination.getOrElse(state.destination)
      state.bestCost = preRoute.size.toDouble
      state.status = Ready
      state.updateCurrentPath(None)
      MovableMetrics.journeysStarted.labels(getClass.getSimpleName).inc()
      report(
        data = Map(
          "event_type"   -> "journey_started",
          "vehicle_id"   -> getEntityId,
          "bicycle_id"   -> getEntityId,
          "origin"       -> origin,
          "destination"  -> destination,
          "route_length" -> preRoute.size,
          "tick"         -> currentTick
        ),
        label = "journey_started"
      )
      if (preRoute.nonEmpty) enterLink()
      else {
        finishJourney("already_at_destination", origin)
        onFinishPrivateVehicle(origin)
        onFinishSpontaneous(None)
        if (!isPersonCentric) selfDestruct()
      }
      return
    }
    val origin = getTripOrigin.getOrElse(state.origin)
    val destination = getTripDestination.getOrElse(state.destination)
    if (journeyReporter.sumoDepartTick.nonEmpty) {
      journeyReporter.sumoRerouteNo += 1
    }
    try
      GPSUtil.calcRouteCompact(originId = origin, destinationId = destination, maxExpansions = Int.MaxValue) match {
        case Some((cost, pathQueue)) =>
          state.bestRoute = Some(pathQueue)
          state.bestCost = cost
          state.status = Ready
          state.updateCurrentPath(None)

          MovableMetrics.journeysStarted.labels(getClass.getSimpleName).inc()
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
            onFinishPrivateVehicle(origin)
            onFinishSpontaneous(None)
            if (!isPersonCentric) selfDestruct()
          }

        case None =>
          GPSMetrics.gpsCannotFindRoute.labels("bicycle").inc()
          logError(s"Failed to calculate route for bicycle ${getEntityId}")
          finishJourney("teleported", destination)
          onFinishPrivateVehicle(destination, wasTeleported = true)
          onFinishSpontaneous(None)
          if (!isPersonCentric) selfDestruct()
      }
    catch {
      case e: Exception =>
        logError(s"Exception during bicycle route request: ${e.getMessage}", e)
        finishJourney("exception", origin)
        onFinishPrivateVehicle(origin)
        onFinishSpontaneous(None)
        if (!isPersonCentric) selfDestruct()
    }
  }

  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit =
    microHandler.handleMicroEnterLink(data, state)

  private def handleMicroUpdate(event: ActorInteractionEvent, data: MicroUpdateData): Unit =
    microHandler.handleMicroUpdate(data, state)

  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit =
    microHandler.handleMicroLeaveLink(data, state)

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = linkHandler.handleEnterLink(event.actorRefId, data, state)

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = linkHandler.handleLeaveLink(event.actorRefId, data, state)

  private def finishJourney(reason: String, finalNode: String): Unit =
    journeyReporter.finishJourney(reason, finalNode, state)

  override protected def applyDriverAttributes(attrs: DriverAttributes): Unit = {
    super.applyDriverAttributes(attrs)
    state.microState.foreach { micro =>
      state.updateMicroState(micro.copy(
        desiredVelocity = micro.desiredVelocity * attrs.maxSpeedFactor,
        reactionTime    = attrs.reactionTime,
        minGap          = micro.minGap * attrs.minGapFactor
      ))
    }
  }

  override protected def onFinish(nodeId: String): Unit = {
    finishJourney("onFinish_called", nodeId)
    onFinishPrivateVehicle(nodeId)
  }

  override def onDestruct(event: DestructEvent): Unit = {
    if (state != null) signalHandler.cancelPendingCapacityRequest(state)
    if (state != null && state.status != Finished) {
      val fallbackNode = Option(getCurrentNode)
        .orElse(state.currentPath.map(_._2))
        .getOrElse("unknown")
      journeyReporter.finishJourney("actor_destructed_before_completion", fallbackNode, state)
      onFinishPrivateVehicle(fallbackNode)
    }
    if (state != null) {
      state.movableBestRoute = None
      state.movableCurrentPath = None
      state.microState = None
    }
  }
}

/** Bicycle companion object.
  */
object Bicycle {
  def apply(properties: Properties): Bicycle =
    new Bicycle(properties)
}
