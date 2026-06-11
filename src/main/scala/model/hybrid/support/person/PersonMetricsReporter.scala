package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.PersonState
import org.interscity.htc.core.metrics.model.hybrid.PersonMetrics

/** Handles all metrics and reporting for person actors.
  *
  * Responsibilities:
  *   - Mode choice metrics
  *   - Trip metrics and leg metrics
  *   - Activity metrics
  *   - Schedule completion metrics
  *
  * @param personId Person actor ID
  * @param reportFn Reporting function for events
  * @param logInfo Info logging function
  */
class PersonMetricsReporter(
  personId: String,
  reportFn: (Map[String, Any], String) => Unit,
  logInfo: String => Unit
) {

  private var modeChoiceDecisionCount: Long = 0L
  private var activityWaitLogCount: Long = 0L

  /** Normalize mode string for consistent metrics.
    */
  def normalizeMode(mode: String): String =
    Option(mode).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("unknown")

  /** Record mode choice metrics.
    *
    * @param requestedMode Originally requested transport mode
    * @param resolvedMode Final resolved transport mode
    * @param source Source of decision ("static", "dynamic", etc.)
    */
  def recordModeChoiceMetrics(
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

  /** Log mode choice decision (sampled).
    *
    * @param originNodeId Origin node
    * @param destinationNodeId Destination node
    * @param requestedMode Requested mode
    * @param resolvedMode Resolved mode
    * @param source Decision source
    * @param logEvery Log every N decisions (0 to disable)
    * @param globalEnabled Global dynamic mode choice setting
    * @param stateEnabled State-specific dynamic mode choice setting
    */
  def maybeLogModeChoiceDecision(
    originNodeId: String,
    destinationNodeId: String,
    requestedMode: String,
    resolvedMode: String,
    source: String,
    logEvery: Int,
    globalEnabled: Boolean,
    stateEnabled: Boolean
  ): Unit = {
    recordModeChoiceMetrics(requestedMode, resolvedMode, source)

    modeChoiceDecisionCount += 1
    if (logEvery > 0 && modeChoiceDecisionCount % logEvery == 0) {
      logInfo(
        s"$personId mode-choice[$modeChoiceDecisionCount] " +
          s"requested=$requestedMode resolved=$resolvedMode source=$source " +
          s"origin=$originNodeId destination=$destinationNodeId " +
          s"global=$globalEnabled state=$stateEnabled"
      )
    }
  }

  /** Report trip started event.
    *
    * @param tripId Trip identifier
    * @param mode Transport mode
    * @param currentTick Current simulation tick
    */
  def reportTripStarted(tripId: String, mode: String, currentTick: Tick): Unit =
    reportFn(
      Map(
        "event_type" -> "trip_started",
        "person_id" -> personId,
        "trip_id" -> tripId,
        "mode" -> mode,
        "departure_time" -> currentTick,
        "tick" -> currentTick
      ),
      "person_trip_started"
    )

  /** Report trip and leg metrics.
    *
    * @param mode Transport mode
    * @param tripId Trip identifier
    * @param departureTime Trip departure tick
    * @param arrivalTick Trip arrival tick
    * @param travelTime Travel time in ticks
    * @param distance Distance traveled (optional)
    * @param waitTime Wait time before trip start (optional)
    * @param currentTick Current simulation tick
    */
  def reportTripAndLegMetrics(
    mode: String,
    tripId: String,
    departureTime: Tick,
    arrivalTick: Tick,
    travelTime: Long,
    distance: Option[Double],
    waitTime: Option[Long],
    currentTick: Tick
  ): Unit = {
    val effectiveWait = waitTime.getOrElse(0L)

    var tripMetrics: Map[String, Any] = Map(
      "event_type" -> "trip_metrics",
      "person_id" -> personId,
      "trip_id" -> tripId,
      "mode" -> mode,
      "departure_time" -> departureTime,
      "arrival_time" -> arrivalTick,
      "tick" -> currentTick
    )
    distance.foreach { d =>
      tripMetrics += ("traveled_distance" -> d)
    }

    reportFn(tripMetrics, "person_trip_metrics")

    reportFn(
      Map(
        "event_type" -> "leg_metrics",
        "person_id" -> personId,
        "trip_id" -> tripId,
        "mode" -> mode,
        "travel_time" -> math.max(0L, travelTime),
        "distance" -> distance.map(Double.box).orNull,
        "wait_time" -> math.max(0L, effectiveWait),
        "tick" -> currentTick
      ),
      "person_leg_metrics"
    )
  }

  /** Report trip completed event.
    *
    * @param vehicleId Vehicle ID that completed the trip
    * @param distanceTraveled Distance traveled in meters
    * @param travelTime Travel time in ticks
    * @param completionReason Reason for trip completion
    * @param totalDistance Person's total distance traveled
    * @param completedTrips Total completed trips count
    * @param currentTick Current simulation tick
    */
  def reportTripCompleted(
    vehicleId: String,
    distanceTraveled: Double,
    travelTime: Long,
    completionReason: String,
    totalDistance: Double,
    completedTrips: Int,
    currentTick: Tick
  ): Unit =
    reportFn(
      Map(
        "event_type" -> "trip_completed",
        "person_id" -> personId,
        "vehicle_id" -> vehicleId,
        "distance_traveled" -> distanceTraveled,
        "travel_time" -> travelTime,
        "completion_reason" -> completionReason,
        "total_distance" -> totalDistance,
        "completed_trips" -> completedTrips,
        "tick" -> currentTick
      ),
      "person_trip_completed"
    )

  /** Report activity started event.
    *
    * @param activityType Type of activity
    * @param activitySequence Sequence number of activity
    * @param nodeId Node where activity takes place
    * @param endTime Scheduled end time
    * @param currentTick Current simulation tick
    */
  def reportActivityStart(
    activityType: String,
    activitySequence: Int,
    nodeId: String,
    endTime: String,
    currentTick: Tick
  ): Unit =
    reportFn(
      Map(
        "event_type" -> "activity_start",
        "person_id" -> personId,
        "activity_type" -> activityType,
        "activity_sequence" -> activitySequence,
        "node_id" -> nodeId,
        "end_time" -> endTime,
        "tick" -> currentTick
      ),
      "person_activity_start"
    )

  /** Report schedule complete event.
    *
    * @param totalTrips Total trips completed
    * @param totalDistance Total distance traveled
    * @param currentTick Current simulation tick
    */
  def reportScheduleComplete(totalTrips: Int, totalDistance: Double, currentTick: Tick): Unit = {
    PersonMetrics.completeSchedule.inc()
    reportFn(
      Map(
        "event_type" -> "schedule_complete",
        "person_id" -> personId,
        "total_trips" -> totalTrips,
        "total_distance" -> totalDistance,
        "tick" -> currentTick
      ),
      "person_schedule_complete"
    )
  }

  /** Record trip start metric.
    *
    * @param activityType Activity type being traveled to
    * @param mode Transport mode
    */
  def recordTripStart(activityType: String, mode: String): Unit =
    if (!mode.equalsIgnoreCase("auto"))
      PersonMetrics.personTripStart.labels(activityType, mode).inc()

  /** Record trip end and completion reason metrics.
    *
    * @param activityType Activity type arrived at
    * @param mode Transport mode used
    * @param completionReason Reason trip completed
    */
  def recordTripEnd(activityType: String, mode: String, completionReason: String): Unit = {
    PersonMetrics.personTripEnd.labels(activityType, mode).inc()
    PersonMetrics.personCompleteTripReason.labels(activityType, mode, completionReason).inc()
  }

  /** Log activity wait (sampled).
    *
    * @param activityType Type of activity
    * @param endTime Activity end time
    * @param effectiveEndTick Effective end tick (with delays)
    * @param currentTick Current simulation tick
    * @param nextTick Next scheduled tick
    * @param logEvery Log every N waits
    * @param logDebug Debug logging function
    */
  def maybeLogActivityWait(
    activityType: String,
    endTime: String,
    effectiveEndTick: Option[Long],
    currentTick: Tick,
    nextTick: Tick,
    logEvery: Int,
    logDebug: String => Unit
  ): Unit = {
    activityWaitLogCount += 1
    if (logEvery > 0 && activityWaitLogCount % logEvery == 0L) {
      logDebug(
        s"$personId waiting activity[$activityWaitLogCount] $activityType " +
          s"endTime=$endTime effectiveEnd=${effectiveEndTick.getOrElse("?")} " +
          s"currentTick=$currentTick nextTick=$nextTick"
      )
    }
  }

  /** Reset activity wait log counter (after starting a new trip).
    */
  def resetActivityWaitLogCounter(): Unit =
    activityWaitLogCount = 0L
}
