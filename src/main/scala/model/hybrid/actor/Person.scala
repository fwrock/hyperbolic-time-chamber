package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick
import core.entity.actor.properties.Properties
import core.util.StringPool
import model.hybrid.entity.state.{Activity, ArrivalLogistics, PersonState}
import model.hybrid.entity.event.data.person.{PersonScheduleCompleteData, StartTripData, TripCompletedData}
import model.hybrid.entity.event.data.bus.{BusRequestUnloadPassengerData, BusUnloadPassengerData, RegisterPassengerData, PTLineNotOperationalData}
import model.hybrid.entity.event.data.subway.{RegisterSubwayPassengerData, SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData}
import model.hybrid.util.{CityMapUtil, GPSUtil}
import model.hybrid.util.strategy.{ModeChoiceResult, ModeChoiceStrategyRegistry}
import model.hybrid.support.person.{PersonScheduleManager, PersonActivityManager, PersonMetricsReporter, PersonModeChoiceHandler, PersonWalkingTripHandler, PersonPTTripHandler, PersonPrivateVehicleTripHandler, PersonTripManager, TripStartResult}

import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.metrics.core.ActorMetrics
import org.interscity.htc.core.metrics.model.hybrid.{GPSMetrics, PersonMetrics}
import org.interscity.htc.core.util.SimulationUtil
import core.actor.trace.ActorTrace

import scala.collection.mutable

/** Person actor - Agent-based person in the simulation.
  *
  * In the person-centric model, Person actors:
  *   - Persist throughout the simulation day
  *   - Manage their daily schedule (activities)
  *   - Make mode choices for trips
  *   - Activate private vehicles (Car, Bicycle, Motorcycle) as needed
  *   - Receive trip completion notifications
  *
  * Lifecycle:
  *   1. Person starts at first activity (Home) 2. Wait until activity endTime 3. Read
  *      nextActivity.arrivalLogistics 4. Execute mode choice (decide transport mode) 5. Send
  *      StartTrip to chosen vehicle 6. Wait for TripCompleted 7. Advance to next activity 8. Repeat
  *      until schedule complete
  *
  * @param properties
  *   Actor properties
  */
class Person(
  private val properties: Properties
) extends SimulationBaseActor[PersonState](
      properties = properties
    ) {

  // Truncation is deferred to the first actSpontaneous so that SimulationManager has already
  // published htc.simulation.duration to SimulatorSettingsRegistry before we run.
  private var scheduleAlreadyTruncated: Boolean = false

  private lazy val globalDynamicModeChoiceEnabled: Boolean =
    try SimulationUtil.loadSimulationConfig().enableDynamicModeChoice
    catch { case _: Exception => false }

  // When set in simulation.json, overrides every person's modeChoiceWeights.includedModes.
  private lazy val globalModeChoiceIncludedModes: Option[Set[String]] =
    try SimulationUtil.loadSimulationConfig().modeChoiceIncludedModes.map(_.map(_.toLowerCase).toSet)
    catch { case _: Exception => None }

  // Sample mode-choice logs to avoid high-volume output in large scenarios.
  private lazy val modeChoiceLogEvery: Int =
    sys.env
      .get("HTC_PERSON_MODE_CHOICE_LOG_EVERY")
      .flatMap(v => scala.util.Try(v.toInt).toOption)
      .orElse(
        scala.util.Try(config.getInt("htc.person.mode-choice-log-every")).toOption
      )
      .getOrElse(1000)

  private var modeChoiceDecisionCount: Long = 0L

  private lazy val activityWaitLogEvery: Int =
    sys.env
      .get("HTC_PERSON_ACTIVITY_WAIT_LOG_EVERY")
      .flatMap(v => scala.util.Try(v.toInt).toOption)
      .orElse(scala.util.Try(config.getInt("htc.person.activity-wait-log-every")).toOption)
      .filter(_ > 0)
      .getOrElse(200)

  private var activityWaitLogCount: Long = 0L

  // ============================================================================
  // Support Classes (lazy initialization - created only when needed)
  // ============================================================================
  
  // Wrapper to adapt sendMessageTo signature from AnyRef to Any
  private def sendMessage(entityId: String, shardId: String, data: Any, eventType: String, actorType: Any): Unit = {
    sendMessageTo(entityId, shardId, data.asInstanceOf[AnyRef], eventType, actorType.asInstanceOf[org.interscity.htc.core.enumeration.CreationTypeEnum])
  }
  
  private lazy val scheduleManager = new PersonScheduleManager(
    personId = getEntityId,
    configProvider = key => scala.util.Try(config.getString(key)).toOption,
    logDebug = logDebug,
    logWarn = logWarn,
    reportFn = report
  )

  private lazy val activityManager = new PersonActivityManager(
    personId = getEntityId,
    scheduleManager = scheduleManager,
    logDebug = logDebug,
    logWarn = logWarn
  )

  private lazy val metricsReporter = new PersonMetricsReporter(
    personId = getEntityId,
    reportFn = report,
    logInfo = logInfo
  )

  private lazy val modeChoiceHandler = new PersonModeChoiceHandler(
    personId = getEntityId,
    globalDynamicModeChoiceEnabled = globalDynamicModeChoiceEnabled,
    globalModeChoiceIncludedModes = globalModeChoiceIncludedModes,
    metricsReporter = metricsReporter,
    logDebug = logDebug,
    logWarn = logWarn
  )

  private lazy val walkingHandler = new PersonWalkingTripHandler(
    personId = getEntityId,
    metricsReporter = metricsReporter,
    reportFn = report,
    logDebug = logDebug,
    logError = logError
  )

  private lazy val ptHandler = new PersonPTTripHandler(
    personId = getEntityId,
    metricsReporter = metricsReporter,
    reportFn = report,
    sendMessageFn = sendMessage,
    logDebug = logDebug,
    logWarn = logWarn
  )

  private lazy val privateVehicleHandler = new PersonPrivateVehicleTripHandler(
    personId = getEntityId,
    sendMessageFn = sendMessage,
    logDebug = logDebug,
    logError = logError
  )

  private lazy val tripManager = new PersonTripManager(
    personId = getEntityId,
    activityManager = activityManager,
    modeChoiceHandler = modeChoiceHandler,
    ptHandler = ptHandler,
    walkingHandler = walkingHandler,
    privateVehicleHandler = privateVehicleHandler,
    metricsReporter = metricsReporter,
    sendMessageFn = sendMessage,
    reportFn = report,
    logDebug = logDebug,
    logWarn = logWarn,
    logError = logError
  )

  // Note: tripManager.setModeChoiceLogEvery(modeChoiceLogEvery) will be called
  // when tripManager is first accessed, configured via PersonModeChoiceHandler

  // ============================================================================
  // Original methods (to be gradually replaced by handlers)
  // ============================================================================

  private def normalizeMode(mode: String): String =
    Option(mode).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("unknown")

  private def recordModeChoiceMetrics(
    requestedMode: String,
    resolvedMode: String,
    source: String
  ): Unit = {
    val requested = normalizeMode(requestedMode)
    val resolved = normalizeMode(resolvedMode)
    val metricSource = normalizeMode(source)

    PersonMetrics.personModeChoiceResolved
      .labels(requested, resolved, metricSource)
      .inc()

    if (requested != resolved)
      PersonMetrics.personModeChoiceChanged
        .labels(requested, resolved, metricSource)
        .inc()
  }

  private def maybeLogModeChoiceDecision(
    originNodeId: String,
    destinationNodeId: String,
    requestedMode: String,
    resolvedMode: String,
    source: String
  ): Unit = {
    recordModeChoiceMetrics(requestedMode, resolvedMode, source)

    modeChoiceDecisionCount += 1
    if (modeChoiceLogEvery > 0 && modeChoiceDecisionCount % modeChoiceLogEvery == 0) {
      logInfo(
        s"${getEntityId} mode-choice[$modeChoiceDecisionCount] " +
          s"requested=$requestedMode resolved=$resolvedMode source=$source " +
          s"origin=$originNodeId destination=$destinationNodeId " +
          s"global=$globalDynamicModeChoiceEnabled state=${state.enableDynamicModeChoice}"
      )
    }
  }

  private def isDynamicModeChoiceEnabled: Boolean =
    globalDynamicModeChoiceEnabled || state.enableDynamicModeChoice

  /** Person should only re-register on the TM after migration if it was actually registered at
    * migration time. During vehicle trips (and PT trips), Person calls onFinishSpontaneous(None)
    * and yields TM ownership to the vehicle. Walking trips keep Person on TM (scheduled to wake at
    * arrival tick), so those are allowed.
    */
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean =
    state != null &&
      state.isSetScheduleOnTimeManager &&
      state.currentTripVehicleId.forall(_ == "walking")

  /** Interns repeated string fields (activity types, transport modes, node IDs, stop IDs, line
    * names) so that all Person actors at city scale share canonical String instances instead of
    * holding independent copies from JSON deserialization.
    */
  override protected def internStateStrings(s: PersonState): PersonState =
    s.withInternedStrings

  /** Truncate activities whose endTime exceeds the simulation duration.
    *
    * Called lazily on the first actSpontaneous (not onStart) so that SimulationManager has
    * already published htc.simulation.duration to SimulatorSettingsRegistry.
    */
  private def applyScheduleTruncationIfNeeded(): Unit =
    if (!scheduleAlreadyTruncated) {
      scheduleAlreadyTruncated = true
      state = scheduleManager.applyTruncationIfNeeded(state)
    }

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    applyScheduleTruncationIfNeeded()

    if (state == null) {
      logWarn(
        s"${getEntityId} actSpontaneous called with null state at tick=$currentTick — unscheduling"
      )
      onFinishSpontaneous(None)
      return
    }
    if (state.ptWaitingSince.isDefined) {
      val waited = currentTick - state.ptWaitingSince.get
      logWarn(
        s"${getEntityId} PT wait timed out after $waited ticks — skipping to next activity"
      )
      state = state.completeTrip(0.0)
      state = state.copy(ptWaitingSince = None)
      advanceToNextActivity()
      return
    }

    if (state.isScheduleComplete) {
      logDebug(
        s"${getEntityId} completed daily schedule (${state.completedTrips} trips, ${state.totalDistanceTraveled}m)"
      )
      PersonMetrics.completeSchedule.inc()
      ActorMetrics.spontaneousEventAfterCompletion.labels(
        getClass.getSimpleName, "spontaneous"
      ).inc()
      notifyVehiclesScheduleComplete()
      onFinishSpontaneous(None, destruct = true)
      return
    }

    if (state.currentTripVehicleId.isDefined) {
      if (state.currentTripVehicleId.contains("walking")) {
        if (state.pendingTransferLegs.nonEmpty) {
          // Journey-internal walk (access or transfer leg) — start next pending leg without
          // advancing the activity index.
          val walkDest = state.currentTripDestinationNodeId
          state = state.completeTrip(0.0)
          state = state.copy(currentPhysicalNodeId = walkDest)
          val nextLeg  = state.pendingTransferLegs.head
          val restLegs = state.pendingTransferLegs.tail
          state = state.copy(pendingTransferLegs = restLegs)
          val destNodeId = nextLeg.alightingNodeId.getOrElse(
            state.nextActivity.map(_.nodeId).getOrElse("")
          )
          state.currentActivity.foreach { act =>
            initiateTrip(act.copy(nodeId = destNodeId), nextLeg)
          }
        } else {
          // Activity-level walk (or final egress walk) — advance to next activity.
          advanceToNextActivity()
        }
        return
      }

      logDebug(
        s"${getEntityId} unexpected spontaneous event during vehicle trip with ${state.currentTripVehicleId.get}"
      )
      onFinishSpontaneous(None)
      return
    }

    state.currentActivity match {
      case Some(activity) =>
        if (isActivityEndTime(activity)) {
          logDebug(
            s"${getEntityId} completing activity ${activity.activityType} at ${activity.nodeId}"
          )
          activityWaitLogCount = 0L
          startNextTrip()
        } else {
          val endTick = currentTick + getTickUntilActivityEnd(activity)
          activityWaitLogCount += 1
          if (activityWaitLogCount % activityWaitLogEvery == 0L)
            logDebug(
              s"${getEntityId} waiting activity[${activityWaitLogCount}] ${activity.activityType} " +
                s"endTime=${activity.endTime} effectiveEnd=${effectiveEndTick(activity)} " +
                s"currentTick=$currentTick nextTick=$endTick"
            )
          onFinishSpontaneous(Some(endTick))
        }

      case None =>
        advanceToNextActivity()
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: TripCompletedData =>
        val (newState, shouldAdvance) = tripManager.handleTripCompleted(state, d, currentTick)
        state = newState
        if (shouldAdvance) advanceToNextActivity()
        
      case d: BusRequestUnloadPassengerData =>
        val (newState, shouldAdvance) = tripManager.handlePTUnloadRequest(event, d.nodeId, "bus", state, currentTick)
        state = newState
        if (shouldAdvance) advanceToNextActivity()
        
      case d: SubwayRequestUnloadPassengerData =>
        val (newState, shouldAdvance) = tripManager.handlePTUnloadRequest(event, d.nodeId, "subway", state, currentTick)
        state = newState
        if (shouldAdvance) advanceToNextActivity()
        
      case d: PTLineNotOperationalData =>
        val (newState, shouldAdvance) = tripManager.handlePTLineNotOperational(d, state, currentTick)
        state = newState
        if (shouldAdvance) advanceToNextActivity()
        
      case _ =>
        logWarn(s"Person event not handled: ${event.eventType}")
    }

  /** Check if current activity's end time has been reached.
    */
  private def isActivityEndTime(activity: Activity): Boolean =
    activityManager.isActivityEndTime(activity, state, currentTick)

  /** Calculate ticks until activity end time.
    */
  private def getTickUntilActivityEnd(activity: Activity): Long =
    activityManager.getTickUntilActivityEnd(activity, state, currentTick)

  private def parseTick(value: String): Option[Long] =
    scheduleManager.parseTick(value)

  private def effectiveEndTick(activity: Activity): Option[Long] =
    scheduleManager.effectiveEndTick(activity, state)

  private def plannedStartTickForActivity(index: Int): Option[Long] = {
    val previousIndex = index - 1
    if (previousIndex >= 0 && previousIndex < state.dailySchedule.length)
      parseTick(state.dailySchedule(previousIndex).endTime)
    else
      None
  }

  private def updateScheduleDelayOnArrival(arrivedActivityIndex: Int): Unit =
    state = scheduleManager.updateScheduleDelayOnArrival(state, arrivedActivityIndex, currentTick)

  private def nextTripId: String =
    s"${getEntityId}:trip:${state.completedTrips + 1}"

  private def markTripStarted(
    vehicleId: String,
    mode: String,
    expectedDistance: Option[Double] = None,
    waitTime: Option[Long] = Some(0L)
  ): Unit = {
    val tripId = nextTripId
    state = state.copy(
      currentTripVehicleId = Some(vehicleId),
      currentTripStartTick = Some(currentTick),
      currentTripMode = Some(StringPool.intern(mode)),
      currentTripId = Some(tripId),
      currentTripDepartureTick = Some(currentTick),
      currentTripExpectedDistance = expectedDistance,
      currentTripWaitTime = waitTime
    )

    report(
      data = Map(
        "event_type" -> "trip_started",
        "person_id" -> getEntityId,
        "trip_id" -> tripId,
        "mode" -> mode,
        "departure_time" -> currentTick,
        "tick" -> currentTick
      ),
      label = "person_trip_started"
    )
  }

  private def reportTripAndLegMetrics(
    mode: String,
    arrivalTick: Tick,
    travelTime: Long,
    distance: Option[Double],
    waitTime: Option[Long] = None
  ): Unit = {
    val departureTime = state.currentTripDepartureTick
      .orElse(state.currentTripStartTick)
      .getOrElse(arrivalTick - math.max(0L, travelTime))
    val tripId = state.currentTripId.getOrElse(nextTripId)
    val effectiveWait = waitTime.orElse(state.currentTripWaitTime).getOrElse(0L)

    var tripMetrics: Map[String, Any] = Map(
      "event_type" -> "trip_metrics",
      "person_id" -> getEntityId,
      "trip_id" -> tripId,
      "mode" -> mode,
      "departure_time" -> departureTime,
      "arrival_time" -> arrivalTick,
      "tick" -> currentTick
    )
    distance.foreach { d =>
      tripMetrics += ("traveled_distance" -> d)
    }

    report(
      data = tripMetrics,
      label = "person_trip_metrics"
    )

    report(
      data = Map(
        "event_type" -> "leg_metrics",
        "person_id" -> getEntityId,
        "trip_id" -> tripId,
        "mode" -> mode,
        "travel_time" -> math.max(0L, travelTime),
        "distance" -> distance.map(Double.box).orNull,
        "wait_time" -> math.max(0L, effectiveWait),
        "tick" -> currentTick
      ),
      label = "person_leg_metrics"
    )
  }

  /** Start trip to next activity.
    */
  private def startNextTrip(): Unit =
    tripManager.startNextTrip(state, currentTick) match {
      case TripStartResult.TripStarted(newState, nextTick) =>
        state = newState
        onFinishSpontaneous(nextTick)
      case TripStartResult.InstantArrival(newState) =>
        state = newState
        advanceToNextActivity()
      case TripStartResult.TripSkipped(newState) =>
        state = newState
        advanceToNextActivity()
      case TripStartResult.ScheduleComplete =>
        notifyVehiclesScheduleComplete()
        onFinishSpontaneous(None, destruct = true)
    }

  /** Returns the person's actual physical position for routing.
    *
    * During multi-leg journeys (between access walk, PT legs, transfer walks, and egress walk)
    * `currentPhysicalNodeId` tracks where the person really is. Outside journeys it falls back
    * to the current activity's node.
    */
  private def currentTripOriginNodeId: String =
    state.currentPhysicalNodeId
      .orElse(state.currentActivity.map(_.nodeId))
      .getOrElse("")

  /** Initiate trip to next activity.
    */
  private def initiateTrip(nextActivity: Activity, logistics: ArrivalLogistics): Unit = {
    val origin = currentTripOriginNodeId
    state.currentActivity match {
      case Some(_) =>
        logistics.mode.toLowerCase match {
          case "car" | "bicycle" | "motorcycle" =>
            initiatePrivateVehicleTrip(origin, nextActivity.nodeId, logistics)
          case "walk" =>
            initiateWalkingTrip(origin, nextActivity.nodeId, logistics.precomputedRoute)
          case "transit" | "bus" | "subway" | "pt" | "mixed" =>
            initiatePTTrip(origin, nextActivity.nodeId, logistics)

          case "auto" if isDynamicModeChoiceEnabled =>
            logWarn(
              s"${getEntityId} unresolved auto logistics reached initiateTrip even with dynamic mode choice enabled; skipping trip"
            )
            PersonMetrics.personTripStart.labels(nextActivity.activityType, "no_viable_mode").inc()
            advanceToNextActivity()

          case "auto" =>
            logWarn(
              s"${getEntityId} auto mode requested but dynamic mode choice is disabled globally or for this person; skipping trip"
            )
            maybeLogModeChoiceDecision(
              originNodeId = origin,
              destinationNodeId = nextActivity.nodeId,
              requestedMode = "auto",
              resolvedMode = "skipped",
              source = "auto_disabled"
            )
            advanceToNextActivity()

          case _ =>
            // TODO: model unsupported modes properly when needed.
            logDebug(
              s"Mode '${logistics.mode}' not yet implemented, advancing to next activity using scheduled time"
            )
            advanceToNextActivity()
        }

      case None =>
        logWarn(s"${getEntityId} has no current activity")
        advanceToNextActivity()
    }
  }

  /** Initiate walking trip (mesoscopic).
    *
    * Calculates route using road network, computes walking time based on distance and walking speed
    * (1.4 m/s typical), and schedules arrival.
    */
  private def initiateWalkingTrip(
    origin: String,
    destination: String,
    precomputedRoute: Option[List[(String, String)]] = None
  ): Unit = {
    val routeResult: Option[(Double, mutable.Queue[(String, String)])] =
      precomputedRoute match {
        case Some(route) => Some((0.0, mutable.Queue(route: _*)))
        case None        => GPSUtil.calcRouteCompactWalking(originId = origin, destinationId = destination, maxExpansions = Int.MaxValue)
      }
    routeResult match {
      case Some((routeCost, routeQueue)) =>
        val totalDistance = calculateRouteDistance(routeQueue)

        val walkingSpeed = 1.4 // m/s

        val walkingTimeSeconds = totalDistance / walkingSpeed
        val walkingTimeTicks = math.ceil(walkingTimeSeconds).toLong

        val arrivalTick = currentTick + walkingTimeTicks

        markTripStarted(
          vehicleId = "walking",
          mode = "walk",
          expectedDistance = Some(totalDistance),
          waitTime = Some(0L)
        )

        logDebug(
          s"${getEntityId} walking from $origin to $destination: " +
            s"${totalDistance.toInt}m, ${walkingTimeTicks}s, arriving at tick $arrivalTick"
        )

        report(
          data = Map(
            "event_type" -> "walking_trip_start",
            "person_id" -> getEntityId,
            "origin" -> origin,
            "destination" -> destination,
            "distance" -> totalDistance,
            "walking_time_ticks" -> walkingTimeTicks,
            "arrival_tick" -> arrivalTick,
            "walking_speed" -> walkingSpeed,
            "tick" -> currentTick
          ),
          label = "person_walking_start"
        )

        state = state.copy(currentTripDestinationNodeId = Some(destination))
        onFinishSpontaneous(Some(arrivalTick))

      case None =>
        logError(s"${getEntityId} cannot find walking route from $origin to $destination")
        GPSMetrics.gpsCannotFindRoute.labels("person_walking").inc()
        advanceToNextActivity()
    }
  }

  /** Initiate public transport trip (Bus or Subway).
    *
    * Person registers at the boarding stop (BusStop/SubwayStation) for the specified line, then
    * unregisters from the TimeManager. The PT vehicle carries the Person and periodically asks "do
    * you want to alight here?" via BusRequestUnloadPassengerData /
    * SubwayRequestUnloadPassengerData. Person responds and, when at the alighting node, advances to
    * next activity.
    *
    * Required ArrivalLogistics fields for PT:
    *   - line: bus/subway line label
    *   - boardingStopId: BusStop/SubwayStation actor ID
    *   - boardingStopClassType: actor class type for shard routing
    *   - alightingNodeId: node where Person should alight
    */
  private def initiatePTTrip(
    origin: String,
    destination: String,
    logistics: ArrivalLogistics
  ): Unit =
    (
      logistics.line,
      logistics.boardingStopId,
      logistics.boardingStopClassType,
      logistics.alightingNodeId
    ) match {
      case (Some(line), Some(stopId), Some(stopClassType), Some(alightingNode)) =>
        val registrationData = logistics.mode.toLowerCase match {
          case "subway" => RegisterSubwayPassengerData(line = line)
          case _        => RegisterPassengerData(label = line)
        }

        sendMessageTo(
          entityId = stopId,
          shardId = stopClassType,
          data = registrationData,
          eventType = "RegisterPassenger",
          actorType = LoadBalancedDistributed
        )
        ActorTrace.trace(getEntityId, currentTick, "person_pt_registered", // #actor-trace
          s"stop=$stopId line=$line alighting=$alightingNode mode=${logistics.mode}") // #actor-trace

        markTripStarted(
          vehicleId = s"pt:${logistics.mode}:$line",
          mode = logistics.mode,
          expectedDistance = None,
          waitTime = Some(0L)
        )
        state = state.copy(
          ptAlightingNodeId = Some(StringPool.intern(alightingNode)),
          ptLine = Some(StringPool.intern(line))
        )

        logDebug(s"${getEntityId} registered at $stopId for $line, alighting at $alightingNode")

        report(
          data = Map(
            "event_type" -> "pt_trip_start",
            "person_id" -> getEntityId,
            "mode" -> logistics.mode,
            "line" -> line,
            "origin" -> origin,
            "destination" -> destination,
            "boarding_stop" -> stopId,
            "alighting_node" -> alightingNode,
            "tick" -> currentTick
          ),
          label = "person_pt_trip_start"
        )

        state = state.copy(ptWaitingSince = Some(currentTick))
        onFinishSpontaneous(Some(currentTick + state.ptWaitTimeoutTicks))

      case _ =>
        // TODO: handle gracefully when PT routing data is partially available.
        logDebug(
          s"${getEntityId} PT trip missing routing info (line=${logistics.line}, " +
            s"boardingStop=${logistics.boardingStopId}, alightingNode=${logistics.alightingNodeId}). " +
            s"Advancing to next activity using scheduled time."
        )
        advanceToNextActivity()
    }

  /** Handle notification that a PT line will not operate.
    *
    * Sent by BusStop when BusStation reports route calculation failure. The trip
    * is skipped and the person advances to the next scheduled activity normally.
    */
  // Note: Removed handlePTLineNotOperational() - now handled by tripManager.handlePTLineNotOperational()

  // Note: Removed handlePTUnloadRequest() - now handled by tripManager.handlePTUnloadRequest()

  /** Calculate total route distance by summing link lengths.
    */
  private def calculateRouteDistance(routeQueue: mutable.Queue[(String, String)]): Double = {
    var totalDistance = 0.0

    val routeCopy = routeQueue.clone()

    while (routeCopy.nonEmpty) {
      val (linkEdgeGraphId, _) = routeCopy.dequeue()

      CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
        case Some(edgeLabel) =>
          totalDistance += edgeLabel.length
        case None =>
          logWarn(s"Edge label $linkEdgeGraphId not found")
      }
    }

    totalDistance
  }

  /** Initiate private vehicle trip.
    */
  private def initiatePrivateVehicleTrip(
    origin: String,
    destination: String,
    logistics: ArrivalLogistics
  ): Unit =
    logistics.vehicle match {
      case Some(vehicleRef) =>
        val startTripData = StartTripData(
          personId         = getEntityId,
          origin           = origin,
          destination      = destination,
          driverAttributes = logistics.driverAttributes,
          startTick        = currentTick,
          precomputedRoute = logistics.precomputedRoute
        )

        sendMessageTo(
          entityId = vehicleRef.id,
          shardId = vehicleRef.classType,
          data = startTripData,
          eventType = "StartTrip",
          actorType = LoadBalancedDistributed
        )

        // Update state
        markTripStarted(
          vehicleId = vehicleRef.id,
          mode = logistics.mode,
          expectedDistance = None,
          waitTime = Some(0L)
        )

        onFinishSpontaneous(None)

      case None =>
        logError(s"${getEntityId} no vehicle specified for mode ${logistics.mode}")
        advanceToNextActivity()
    }

  /** Cancel a pending PT-wait timeout.
    *
    * When person is waiting at a transit stop, [[initiatePTTrip]] schedules a future TM wakeup
    * via `onFinishSpontaneous(Some(timeoutTick))`. Call this method as soon as the PT trip
    * resolves (vehicle delivered or line not operational) so the scheduled wakeup is removed
    * from the TimeManager before [[advanceToNextActivity]] adds the next-activity end-tick.
    */
  private def cancelPTWait(): Unit =
    if (state.ptWaitingSince.isDefined) {
      state = state.copy(ptWaitingSince = None)
      onFinishSpontaneous(None) // removes the timeout tick from TM scheduledActors
    }

  // Note: Removed handleTripCompleted() - now handled by tripManager.handleTripCompleted()

  /** Advance to next activity in schedule.
    */
  private def advanceToNextActivity(): Unit = {
    ActorTrace.trace(getEntityId, currentTick, "person_activity_advance", // #actor-trace
      s"from=${state.currentActivity.map(_.activityType).getOrElse("none")} idx=${state.currentActivityIndex} next=${state.nextActivity.map(_.activityType).getOrElse("none")}") // #actor-trace
    
    // Handle walking trip completion
    if (state.currentTripVehicleId.contains("walking")) {
      state.currentTripStartTick.foreach { startTick =>
        val travelTime = currentTick - startTick
        walkingHandler.reportWalkingCompleted(travelTime, currentTick)
      }
      state = state.completeTrip(0.0)
    }

    state = activityManager.advanceActivity(state)
    updateScheduleDelayOnArrival(state.currentActivityIndex)

    state.currentActivity match {
      case Some(activity) =>
        logDebug(s"${getEntityId} arrived at ${activity.activityType} (${activity.nodeId})")
        metricsReporter.reportActivityStart(
          activityType = activity.activityType,
          activitySequence = activity.sequence,
          nodeId = activity.nodeId,
          endTime = activity.endTime,
          currentTick = currentTick
        )
        
        val endTick =
          effectiveEndTick(activity)
            .map(effectiveTick => Math.max(currentTick + 1, effectiveTick))
            .getOrElse(currentTick + 1)
        onFinishSpontaneous(Some(endTick))

      case None =>
        logDebug(s"${getEntityId} completed all activities")
        metricsReporter.reportScheduleComplete(
          totalTrips = state.completedTrips,
          totalDistance = state.totalDistanceTraveled,
          currentTick = currentTick
        )
        tripManager.notifyVehiclesScheduleComplete(state)
        onFinishSpontaneous(None, destruct = true)
    }
  }

  /** Send PersonScheduleCompleteData to all owned private vehicles.
    */
  private def notifyVehiclesScheduleComplete(): Unit =
    state.ownedVehicles.foreach {
      case (_, vehicleRef) =>
        sendMessageTo(
          entityId = vehicleRef.id,
          shardId = vehicleRef.classType,
          data = PersonScheduleCompleteData(personId = getEntityId),
          eventType = "PersonScheduleComplete",
          actorType = LoadBalancedDistributed
        )
    }
}

/** Person companion object.
  */
object Person {
  def apply(properties: Properties): Person =
    new Person(properties)
}
