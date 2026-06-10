package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick
import core.entity.actor.properties.Properties
import core.util.StringPool
import model.hybrid.entity.state.{Activity, PersonState}
import model.hybrid.entity.event.data.person.{PersonScheduleCompleteData, TripCompletedData}
import model.hybrid.entity.event.data.bus.{BusRequestUnloadPassengerData, BusUnloadPassengerData, PTLineNotOperationalData}
import model.hybrid.entity.event.data.subway.{SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData}
import model.hybrid.support.person.{PersonScheduleManager, PersonActivityManager, PersonMetricsReporter, PersonModeChoiceHandler, PersonWalkingTripHandler, PersonPTTripHandler, PersonPrivateVehicleTripHandler, PersonTripManager, TripStartResult}

import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.metrics.core.ActorMetrics
import org.interscity.htc.core.metrics.model.hybrid.PersonMetrics
import org.interscity.htc.core.util.SimulationUtil
import core.actor.trace.ActorTrace

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

  // ============================================================================
  // Original methods (to be gradually replaced by handlers)
  // ============================================================================

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
      tripManager.notifyVehiclesScheduleComplete(state)
      onFinishSpontaneous(None, destruct = true)
      return
    }

    if (state.currentTripVehicleId.isDefined) {
      if (state.currentTripVehicleId.contains("walking")) {
        if (state.pendingTransferLegs.nonEmpty) {
          // Journey-internal walk — start next pending leg without advancing activity
          val nextLeg = state.pendingTransferLegs.head
          val destNodeId = nextLeg.alightingNodeId.getOrElse(
            state.nextActivity.map(_.nodeId).getOrElse("")
          )
          state = state.completeTrip(0.0).copy(
            currentPhysicalNodeId = state.currentTripDestinationNodeId,
            pendingTransferLegs = state.pendingTransferLegs.tail
          )
          
          // Start next leg
          val origin = state.currentPhysicalNodeId
            .orElse(state.currentActivity.map(_.nodeId))
            .getOrElse("")
            
          nextLeg.mode.toLowerCase match {
            case "walk" =>
              walkingHandler.initiateWalkingTrip(
                origin, destNodeId, nextLeg.precomputedRoute, state, currentTick, logWarn
              ) match {
                case Some((updatedState, arrivalTick)) =>
                  state = tripManager.markTripStarted(
                    updatedState, "walking", "walk", None, Some(0L), currentTick
                  )
                  onFinishSpontaneous(Some(arrivalTick))
                case None =>
                  advanceToNextActivity()
              }
              
            case "transit" | "bus" | "subway" | "pt" | "mixed" =>
              ptHandler.initiatePTTrip(origin, destNodeId, nextLeg, state, currentTick) match {
                case Some((updatedState, timeoutTick)) =>
                  state = tripManager.markTripStarted(
                    updatedState,
                    s"pt:${nextLeg.mode}:${nextLeg.line.getOrElse("unknown")}",
                    nextLeg.mode,
                    None,
                    Some(0L),
                    currentTick
                  )
                  onFinishSpontaneous(Some(timeoutTick))
                case None =>
                  advanceToNextActivity()
              }
              
            case "car" | "bicycle" | "motorcycle" =>
              if (privateVehicleHandler.initiatePrivateVehicleTrip(
                origin, destNodeId, nextLeg, currentTick
              )) {
                state = tripManager.markTripStarted(
                  state,
                  nextLeg.vehicle.map(_.id).getOrElse("unknown"),
                  nextLeg.mode,
                  None,
                  Some(0L),
                  currentTick
                )
                onFinishSpontaneous(None)
              } else {
                advanceToNextActivity()
              }
              
            case _ =>
              logWarn(s"${getEntityId} unsupported mode ${nextLeg.mode} in multi-leg journey")
              advanceToNextActivity()
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
        if (activityManager.isActivityEndTime(activity, state, currentTick)) {
          logDebug(
            s"${getEntityId} completing activity ${activity.activityType} at ${activity.nodeId}"
          )
          activityWaitLogCount = 0L
          startNextTrip()
        } else {
          val endTick = currentTick + activityManager.getTickUntilActivityEnd(activity, state, currentTick)
          activityWaitLogCount += 1
          if (activityWaitLogCount % activityWaitLogEvery == 0L)
            logDebug(
              s"${getEntityId} waiting activity[${activityWaitLogCount}] ${activity.activityType} " +
                s"endTime=${activity.endTime} effectiveEnd=${scheduleManager.effectiveEndTick(activity, state)} " +
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
        tripManager.notifyVehiclesScheduleComplete(state)
        onFinishSpontaneous(None, destruct = true)
    }







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
    state = scheduleManager.updateScheduleDelayOnArrival(state, state.currentActivityIndex, currentTick)

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
          scheduleManager.effectiveEndTick(activity, state)
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
}

/** Person companion object.
  */
object Person {
  def apply(properties: Properties): Person =
    new Person(properties)
}
