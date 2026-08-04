package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.types.Tick
import core.actor.manager.loadbalance.migration.MigrationSnapshot

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.*
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.hybrid.util.{ CityMapUtil, GPSUtil, SpeedUtil }
import org.interscity.htc.model.hybrid.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.hybrid.support.motorcycle.{
  MotorcycleJourneyReporter, MotorcycleLinkHandler, MotorcycleMicroHandler, MotorcycleSignalHandler
}
import org.interscity.htc.model.hybrid.entity.state.{ DriverAttributes, MicroMotorcycleState, MotorcycleState }
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.metrics.model.hybrid.{ GPSMetrics, MovableMetrics }
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import core.util.StringPool

/** Motorcycle actor - NEW vehicle type for hybrid simulator.
  *
  * NOW A PRIVATE VEHICLE ASSET (Person-Centric Model):
  *   - Passive (Parked) by default
  *   - Activated by Person via StartTrip message
  *   - Configured with person's DriverAttributes
  *   - Reports back with TripCompleted
  *   - Higher acceleration than cars (3.5 m/s² vs 2.6 m/s²)
  *   - Can filter between lanes (lane splitting)
  *   - Smaller vehicle, more maneuverable (2.5m)
  *   - Higher speeds possible
  *   - Accepts smaller gaps
  *
  * MESO mode:
  *   - Aggregate motorcycle flow
  *   - Higher speeds than cars
  *
  * MICRO mode:
  *   - Detailed positioning with motorcycle-specific parameters
  *   - Aggressive behavior (configurable)
  *   - Lane filtering capability
  *   - Gap acceptance modeling
  *   - Faster acceleration/deceleration
  *
  * @param properties
  *   Actor properties
  */
class Motorcycle(
  private val properties: Properties
) extends Movable[MotorcycleState](
      properties = properties
    )
    with PrivateVehicle[MotorcycleState] {

  override protected def internStateStrings(s: MotorcycleState): MotorcycleState = {
    val copied = s.copy(
      origin      = StringPool.intern(s.origin),
      destination = StringPool.intern(s.destination)
    )
    // copy() only replicates MotorcycleState constructor params; restore MovableState vars
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

  /** Aggressiveness factor [0.0 - 1.0] for motorcycle behavior.
    */
  private val aggressiveness: Double = 0.7

  /** Maximum simulation end tick - vehicles must finish by this tick. */
  private lazy val simulationEndTick: Tick =
    model.hybrid.util.VehicleSimulationConfig.simulationEndTick

  private var signalWaitUntilTick: Option[Tick] = None

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

  private lazy val journeyReporter = new MotorcycleJourneyReporter(
    reportFn        = (data, label) => report(data = data, label = label),
    entityIdFn      = () => getEntityId,
    currentTickFn   = () => currentTick,
    tripOriginFn    = () => getTripOrigin,
    tripDestFn      = () => getTripDestination,
    tripStartTickFn = () => getTripStartTick,
    driverAttrsFn   = () => getDriverAttributes
  )

  private lazy val microHandler = new MotorcycleMicroHandler(
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
    aggressivenessFn         = () => aggressiveness,
    setCurrentLinkIdFn       = id => currentLinkId = id,
    setLinkEntryTickFn       = t => linkEntryTick = t,
    getLinkEntryTickFn       = () => linkEntryTick,
    getCurrentLinkIdFn       = () => currentLinkId,
    microUpdateReportEvery   = microUpdateReportEvery
  )

  private lazy val linkHandler = new MotorcycleLinkHandler(
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

  private lazy val signalHandler = new MotorcycleSignalHandler(
    reportFn                     = (data, label) => report(data = data, label = label),
    entityIdFn                   = () => getEntityId,
    currentTickFn                = () => currentTick,
    journeyReporter              = journeyReporter,
    onFinishSpontaneousFn        = tick => onFinishSpontaneous(tick),
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
    aggressivenessFn             = () => aggressiveness,
    setSignalWaitUntilTickFn     = t => signalWaitUntilTick = t
  )

  // ===== PrivateVehicle Accessor Methods =====

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
    journeyReporter.reset()
    state.bestRoute = None
    state.deactivateMicroMode()
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null) {
      logWarn(
        "Motorcycle state is null, cannot process spontaneous event — sending FinishEvent to unblock TimeManager"
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
        s"Motorcycle ${getEntityId} exceeded simulation end time ($simulationEndTick) at tick $currentTick, force-finishing."
      )
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
        // If movableStatus is already Waiting, enterLink() was already called and we're
        // waiting on the Link's LinkInfoData reply to flip state.status away from Ready —
        // re-calling enterLink() here would resend a duplicate EnterLinkData for a link
        // this motorcycle is already registered on. Just poll again next tick instead.
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
            leavingLink()
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

      case Finished =>
        onFinishSpontaneous()

      case WaitingSignalState =>
        // Should never actually fire — requestSignalState() no longer self-schedules
        // after sending; only handleSignalState (the Node's reply) resolves this status.
        // Do not resend the request if reached anyway (see Car.scala for rationale).
        logWarn(s"Motorcycle ${getEntityId}: unexpected actSpontaneous while WaitingSignalState at tick=$currentTick")
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
      case d: SignalStateData    => handleSignalState(event, d)
      case d: MicroEnterLinkData => handleMicroEnterLink(event, d)
      case d: MicroUpdateData    => handleMicroUpdate(event, d)
      case d: MicroLeaveLinkData => handleMicroLeaveLink(event, d)
      case _                     => super.actInteractWith(event)
    }
  }

  private def requestSignalState(): Unit = signalHandler.requestSignalState(state)

  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit =
    signalHandler.handleSignalState(data, state)

  override protected def microMaxAcceleration: Double = 3.5
  override protected def microMaxDeceleration: Double = 5.0

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
          "event_type"    -> "journey_started",
          "vehicle_id"    -> getEntityId,
          "motorcycle_id" -> getEntityId,
          "origin"        -> origin,
          "destination"   -> destination,
          "route_length"  -> preRoute.size,
          "aggressiveness" -> aggressiveness,
          "tick"          -> currentTick
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
              "motorcycle_id" -> getEntityId,
              "origin" -> origin,
              "destination" -> destination,
              "route_length" -> pathQueue.size,
              "aggressiveness" -> aggressiveness,
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
          GPSMetrics.gpsCannotFindRoute.labels("motorcycle").inc()
          logError(s"Failed to calculate route for motorcycle ${getEntityId}")
          finishJourney("teleported", destination)
          onFinishPrivateVehicle(destination, wasTeleported = true)
          onFinishSpontaneous(None)
          if (!isPersonCentric) selfDestruct()
      }
    catch {
      case e: Exception =>
        logError(s"Exception during motorcycle route request: ${e.getMessage}", e)
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
        desiredVelocity  = micro.desiredVelocity * attrs.maxSpeedFactor,
        reactionTime     = attrs.reactionTime,
        minGap           = micro.minGap * attrs.minGapFactor,
        aggressiveness   = attrs.aggressiveness,
        maxAcceleration  = micro.maxAcceleration * (0.9 + 0.2 * attrs.aggressiveness)
      ))
    }
  }

  override protected def onFinish(nodeId: String): Unit = {
    finishJourney("onFinish_called", nodeId)
    onFinishPrivateVehicle(nodeId)
  }

  override def onDestruct(event: DestructEvent): Unit = {
    if (state != null && state.status != Finished) {
      val fallbackNode = Option(getCurrentNode)
        .orElse(state.movableCurrentPath.map(_._2))
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

/** Motorcycle companion object.
  */
object Motorcycle {
  def apply(properties: Properties): Motorcycle =
    new Motorcycle(properties)

  /** Create motorcycle with custom aggressiveness.
    *
    * @param properties
    *   Actor properties
    * @param aggressiveness
    *   Aggressiveness factor [0.0 - 1.0]
    */
  def withAggressiveness(properties: Properties, aggressiveness: Double): Motorcycle =
    new Motorcycle(properties) {
      val customAggressiveness: Double = math.max(0.0, math.min(1.0, aggressiveness))
    }
}
