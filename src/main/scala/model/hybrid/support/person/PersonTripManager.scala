package org.interscity.htc
package model.hybrid.support.person

import core.entity.event.ActorInteractionEvent
import core.types.Tick
import model.hybrid.entity.state.{Activity, ArrivalLogistics, PersonState}
import model.hybrid.entity.event.data.person.{PersonScheduleCompleteData, TripCompletedData}
import model.hybrid.entity.event.data.bus.PTLineNotOperationalData
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics
import core.actor.trace.ActorTrace

/** Coordinates all trip types for person actors.
  *
  * Responsibilities:
  *   - Start next trip in schedule
  *   - Coordinate mode choice and trip initiation
  *   - Handle trip completion from vehicles
  *   - Manage multi-leg journeys
  *   - Mark trip started/ended states
  *
  * @param personId Person actor ID
  * @param activityManager Activity manager
  * @param modeChoiceHandler Mode choice handler
  * @param ptHandler Public transport handler
  * @param walkingHandler Walking trip handler
  * @param privateVehicleHandler Private vehicle handler
  * @param metricsReporter Metrics reporter
  * @param sendMessageFn Function to send messages to other actors
  * @param reportFn Reporting function for events
  * @param logDebug Debug logging function
  * @param logWarn Warning logging function
  * @param logError Error logging function
  */
class PersonTripManager(
  personId: String,
  activityManager: PersonActivityManager,
  modeChoiceHandler: PersonModeChoiceHandler,
  ptHandler: PersonPTTripHandler,
  walkingHandler: PersonWalkingTripHandler,
  privateVehicleHandler: PersonPrivateVehicleTripHandler,
  metricsReporter: PersonMetricsReporter,
  sendMessageFn: (String, String, Any, String, Any) => Unit,
  reportFn: (Map[String, Any], String) => Unit,
  logDebug: String => Unit,
  logWarn: String => Unit,
  logError: String => Unit
) {

  private var modeChoiceLogEvery: Int = 1000

  /** Set mode choice logging sample rate.
    *
    * @param logEvery Log every N decisions
    */
  def setModeChoiceLogEvery(logEvery: Int): Unit =
    modeChoiceLogEvery = logEvery

  /** Generate next trip ID.
    *
    * @param state Current person state
    * @return Trip ID string
    */
  def nextTripId(state: PersonState): String =
    s"${personId}:trip:${state.completedTrips + 1}"

  /** Mark trip as started in state.
    *
    * @param state Current person state
    * @param vehicleId Vehicle ID
    * @param mode Transport mode
    * @param expectedDistance Expected distance (optional)
    * @param waitTime Wait time before trip start
    * @param currentTick Current simulation tick
    * @return Updated PersonState
    */
  def markTripStarted(
    state: PersonState,
    vehicleId: String,
    mode: String,
    expectedDistance: Option[Double],
    waitTime: Option[Long],
    currentTick: Tick
  ): PersonState = {
    val tripId = nextTripId(state)
    metricsReporter.reportTripStarted(tripId, mode, currentTick)

    state.copy(
      currentTripVehicleId = Some(vehicleId),
      currentTripStartTick = Some(currentTick),
      currentTripMode = Some(mode),
      currentTripId = Some(tripId),
      currentTripDepartureTick = Some(currentTick),
      currentTripExpectedDistance = expectedDistance,
      currentTripWaitTime = waitTime
    )
  }

  /** Start trip to next activity.
    *
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @return Trip start result
    */
  def startNextTrip(state: PersonState, currentTick: Tick): TripStartResult =
    state.nextActivity match {
      case Some(nextActivity) =>
        nextActivity.arrivalLogistics match {
          case Some(logistics) =>
            val originNodeId = state.currentActivity.map(_.nodeId).getOrElse("")
            val modeChoiceResult = modeChoiceHandler.executeModeChoice(
              originNodeId = originNodeId,
              destinationNodeId = nextActivity.nodeId,
              logistics = logistics,
              state = state,
              modeChoiceLogEvery = modeChoiceLogEvery
            )

            val effectiveLogistics = modeChoiceResult.logistics
            val updatedState = state.copy(pendingTransferLegs = modeChoiceResult.pendingLegs)

            ActorTrace.trace(personId, currentTick, "person_trip_start",
              s"from=$originNodeId to=${nextActivity.nodeId} mode=${effectiveLogistics.mode} " +
                s"activity=${nextActivity.activityType} instant=${effectiveLogistics.instant}")

            if (effectiveLogistics.instant) {
              logDebug(
                s"$personId instant transition to ${nextActivity.nodeId} (instant=true)"
              )
              TripStartResult.InstantArrival(updatedState)
            } else {
              if (!effectiveLogistics.mode.equalsIgnoreCase("auto"))
                metricsReporter.recordTripStart(nextActivity.activityType, effectiveLogistics.mode)
              initiateTrip(updatedState, nextActivity, effectiveLogistics, currentTick)
            }

          case None =>
            logDebug(s"$personId instant arrival at ${nextActivity.nodeId} (no logistics)")
            TripStartResult.InstantArrival(state)
        }

      case None =>
        logDebug(s"$personId has no more activities, finishing")
        TripStartResult.ScheduleComplete
    }

  /** Initiate trip to next activity.
    *
    * @param state Current person state
    * @param nextActivity Next activity
    * @param logistics Arrival logistics
    * @param currentTick Current simulation tick
    * @return Trip start result
    */
  private def initiateTrip(
    state: PersonState,
    nextActivity: Activity,
    logistics: ArrivalLogistics,
    currentTick: Tick
  ): TripStartResult = {
    val origin = modeChoiceHandler.currentTripOriginNodeId(state)

    logistics.mode.toLowerCase match {
      case "car" | "bicycle" | "motorcycle" =>
        if (privateVehicleHandler.initiatePrivateVehicleTrip(origin, nextActivity.nodeId, logistics, currentTick)) {
          val updatedState = markTripStarted(
            state,
            vehicleId = logistics.vehicle.map(_.id).getOrElse("unknown"),
            mode = logistics.mode,
            expectedDistance = None,
            waitTime = Some(0L),
            currentTick = currentTick
          )
          TripStartResult.TripStarted(updatedState, None) // No next tick, vehicle controls timing
        } else {
          TripStartResult.TripSkipped(state) // Vehicle missing
        }

      case "walk" =>
        walkingHandler.initiateWalkingTrip(
          origin,
          nextActivity.nodeId,
          logistics.precomputedRoute,
          state,
          currentTick,
          logWarn
        ) match {
          case Some((stateWithRoute, arrivalTick)) =>
            val updatedState = markTripStarted(
              stateWithRoute,
              vehicleId = "walking",
              mode = "walk",
              expectedDistance = stateWithRoute.currentTripExpectedDistance,
              waitTime = Some(0L),
              currentTick = currentTick
            )
            TripStartResult.TripStarted(updatedState, Some(arrivalTick))

          case None =>
            TripStartResult.TripSkipped(state) // Route not found
        }

      case "transit" | "bus" | "subway" | "pt" | "mixed" =>
        ptHandler.initiatePTTrip(
          origin,
          nextActivity.nodeId,
          logistics,
          state,
          currentTick
        ) match {
          case Some((stateWithPT, timeoutTick)) =>
            val updatedState = markTripStarted(
              stateWithPT,
              vehicleId = s"pt:${logistics.mode}:${logistics.line.getOrElse("unknown")}",
              mode = logistics.mode,
              expectedDistance = None,
              waitTime = Some(0L),
              currentTick = currentTick
            )
            TripStartResult.TripStarted(updatedState, Some(timeoutTick))

          case None =>
            logDebug(
              s"$personId PT trip missing routing info. Advancing to next activity using scheduled time."
            )
            TripStartResult.TripSkipped(state)
        }

      case "auto" if modeChoiceHandler.isDynamicModeChoiceEnabled(state) =>
        logWarn(
          s"$personId unresolved auto logistics reached initiateTrip even with dynamic mode choice enabled; skipping trip"
        )
        metricsReporter.recordTripStart(nextActivity.activityType, "no_viable_mode")
        TripStartResult.TripSkipped(state)

      case "auto" =>
        logWarn(
          s"$personId auto mode requested but dynamic mode choice is disabled; skipping trip"
        )
        TripStartResult.TripSkipped(state)

      case _ =>
        logDebug(s"$personId mode '${logistics.mode}' not yet implemented, advancing to next activity")
        TripStartResult.TripSkipped(state)
    }
  }

  /** Handle trip completion from vehicle.
    *
    * @param state Current person state
    * @param data Trip completed data
    * @param currentTick Current simulation tick
    * @return (updated state, should advance to next activity)
    */
  def handleTripCompleted(
    state: PersonState,
    data: TripCompletedData,
    currentTick: Tick
  ): (PersonState, Boolean) = {
    if (state.currentTripVehicleId.isEmpty) {
      logWarn(
        s"$personId received TripCompleted from ${data.vehicleId} while not on any trip " +
          s"— likely stale after PT timeout, ignoring"
      )
      return (state, false)
    }

    logDebug(
      s"$personId received trip completion from ${data.vehicleId}: " +
        s"${data.distanceTraveled}m in ${data.travelTime} ticks, reason: ${data.completionReason}"
    )

    val currentActivityType = state.currentActivity.map(_.activityType).getOrElse("unknown")
    val currentMode = state.currentTripMode.getOrElse("unknown")

    metricsReporter.recordTripEnd(currentActivityType, currentMode, data.completionReason)

    if (data.wasTeleported)
      GPSMetrics.teleportCount.labels(currentMode).inc()

    val departureTime = state.currentTripDepartureTick
      .orElse(state.currentTripStartTick)
      .getOrElse(currentTick - data.travelTime)
    val tripId = state.currentTripId.getOrElse(nextTripId(state))

    metricsReporter.reportTripAndLegMetrics(
      mode = currentMode,
      tripId = tripId,
      departureTime = departureTime,
      arrivalTick = currentTick,
      travelTime = data.travelTime,
      distance = Some(data.distanceTraveled),
      waitTime = state.currentTripWaitTime,
      currentTick = currentTick
    )

    metricsReporter.reportTripCompleted(
      vehicleId = data.vehicleId,
      distanceTraveled = data.distanceTraveled,
      travelTime = data.travelTime,
      completionReason = data.completionReason,
      totalDistance = state.totalDistanceTraveled + data.distanceTraveled,
      completedTrips = state.completedTrips + 1,
      currentTick = currentTick
    )

    val destinationNodeId = state.nextActivity.map(_.nodeId).getOrElse("")
    var updatedState = state.completeTrip(data.distanceTraveled)
    updatedState = privateVehicleHandler.updateVehicleLocation(currentMode, destinationNodeId, updatedState)

    (updatedState, true)
  }

  /** Handle PT line not operational.
    *
    * @param data PT line not operational data
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @return (updated state, should advance to next activity)
    */
  def handlePTLineNotOperational(
    data: PTLineNotOperationalData,
    state: PersonState,
    currentTick: Tick
  ): (PersonState, Boolean) = {
    val currentActivityType = state.currentActivity.map(_.activityType).getOrElse("unknown")
    val (updatedState, travelTime) = ptHandler.handlePTLineNotOperational(
      data.line,
      state,
      currentTick,
      currentActivityType
    )

    val departureTime = updatedState.currentTripDepartureTick
      .orElse(updatedState.currentTripStartTick)
      .getOrElse(currentTick - travelTime)
    val tripId = updatedState.currentTripId.getOrElse(nextTripId(updatedState))
    val currentMode = updatedState.currentTripMode.getOrElse("pt")

    metricsReporter.reportTripAndLegMetrics(
      mode = currentMode,
      tripId = tripId,
      departureTime = departureTime,
      arrivalTick = currentTick,
      travelTime = travelTime,
      distance = None,
      waitTime = updatedState.currentTripWaitTime,
      currentTick = currentTick
    )

    (updatedState, true)
  }

  /** Handle PT unload request.
    *
    * @param event Actor interaction event
    * @param nodeId Node ID where vehicle is
    * @param ptType PT type ("bus" or "subway")
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @return Updated state and whether to advance to next activity
    */
  def handlePTUnloadRequest(
    event: ActorInteractionEvent,
    nodeId: String,
    ptType: String,
    state: PersonState,
    currentTick: Tick
  ): (PersonState, Boolean) = {
    val (updatedState, isArrival, maybeTravelTime) = ptHandler.handlePTUnloadRequest(
      event,
      nodeId,
      ptType,
      state,
      currentTick
    )

    if (isArrival) {
      maybeTravelTime.foreach { travelTime =>
        val departureTime = updatedState.currentTripDepartureTick
          .orElse(updatedState.currentTripStartTick)
          .getOrElse(currentTick - travelTime)
        val tripId = updatedState.currentTripId.getOrElse(nextTripId(updatedState))
        val currentMode = updatedState.currentTripMode.getOrElse(ptType)

        metricsReporter.reportTripAndLegMetrics(
          mode = currentMode,
          tripId = tripId,
          departureTime = departureTime,
          arrivalTick = currentTick,
          travelTime = travelTime,
          distance = None,
          waitTime = updatedState.currentTripWaitTime,
          currentTick = currentTick
        )
      }

      // Check if there are pending transfer legs
      if (ptHandler.hasPendingTransferLegs(updatedState)) {
        (updatedState, false) // Don't advance, will start transfer leg
      } else {
        (updatedState, true) // Advance to next activity
      }
    } else {
      (updatedState, false)
    }
  }

  /** Send schedule complete notification to all owned vehicles.
    *
    * @param state Current person state
    */
  def notifyVehiclesScheduleComplete(state: PersonState): Unit =
    state.ownedVehicles.foreach { case (_, vehicleRef) =>
      sendMessageFn(
        vehicleRef.id,
        vehicleRef.classType,
        PersonScheduleCompleteData(personId = personId),
        "PersonScheduleComplete",
        LoadBalancedDistributed
      )
    }
}

/** Result of trip start operation.
  */
sealed trait TripStartResult
object TripStartResult {
  /** Trip started successfully.
    *
    * @param state Updated person state
    * @param nextTick Next tick to schedule (None for vehicle-controlled timing)
    */
  case class TripStarted(state: PersonState, nextTick: Option[Tick]) extends TripStartResult

  /** Trip skipped (route not found, vehicle missing, etc.).
    *
    * @param state Current person state (unchanged)
    */
  case class TripSkipped(state: PersonState) extends TripStartResult

  /** Instant arrival (no travel needed).
    *
    * @param state Updated person state
    */
  case class InstantArrival(state: PersonState) extends TripStartResult

  /** Schedule complete, no more trips.
    */
  case object ScheduleComplete extends TripStartResult
}
