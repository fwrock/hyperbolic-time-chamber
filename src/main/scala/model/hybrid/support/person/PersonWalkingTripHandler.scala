package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.PersonState
import model.hybrid.util.{CityMapUtil, GPSUtil}
import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics

import scala.collection.mutable

/** Handles walking trips for person actors.
  *
  * Responsibilities:
  *   - Calculate walking routes
  *   - Compute walking time based on distance
  *   - Report walking trip events
  *   - Handle route calculation failures
  *
  * @param personId Person actor ID
  * @param metricsReporter Metrics reporter
  * @param reportFn Reporting function for events
  * @param logDebug Debug logging function
  * @param logError Error logging function
  */
class PersonWalkingTripHandler(
  personId: String,
  metricsReporter: PersonMetricsReporter,
  reportFn: (Map[String, Any], String) => Unit,
  logDebug: String => Unit,
  logError: String => Unit
) {

  private val walkingSpeed: Double = 1.4 // m/s (typical human walking speed)

  /** Calculate total route distance by summing link lengths.
    *
    * @param routeQueue Queue of (linkId, nodeId) pairs
    * @param logWarn Warning logging function
    * @return Total distance in meters
    */
  def calculateRouteDistance(routeQueue: mutable.Queue[(String, String)], logWarn: String => Unit): Double = {
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

  /** Initiate walking trip (mesoscopic).
    *
    * Calculates route using road network, computes walking time based on distance and walking speed,
    * and schedules arrival. Returns updated state and arrival tick, or None if route not found.
    *
    * @param origin Origin node ID
    * @param destination Destination node ID
    * @param precomputedRoute Optional precomputed route
    * @param state Current person state
    * @param currentTick Current simulation tick
    * @param logWarn Warning logging function
    * @return Some((updated state, arrival tick)) if successful, None if route not found
    */
  def initiateWalkingTrip(
    origin: String,
    destination: String,
    precomputedRoute: Option[List[(String, String)]],
    state: PersonState,
    currentTick: Tick,
    logWarn: String => Unit
  ): Option[(PersonState, Tick)] = {
    val routeResult: Option[(Double, mutable.Queue[(String, String)])] =
      precomputedRoute match {
        case Some(route) => Some((0.0, mutable.Queue(route: _*)))
        case None        => GPSUtil.calcRouteCompactWalking(
            originId = origin,
            destinationId = destination,
            maxExpansions = Int.MaxValue
          )
      }

    routeResult match {
      case Some((routeCost, routeQueue)) =>
        val totalDistance = calculateRouteDistance(routeQueue, logWarn)
        val walkingTimeSeconds = totalDistance / walkingSpeed
        val walkingTimeTicks = math.ceil(walkingTimeSeconds).toLong
        val arrivalTick = currentTick + walkingTimeTicks

        logDebug(
          s"$personId walking from $origin to $destination: " +
            s"${totalDistance.toInt}m, ${walkingTimeTicks}s, arriving at tick $arrivalTick"
        )

        reportFn(
          Map(
            "event_type" -> "walking_trip_start",
            "person_id" -> personId,
            "origin" -> origin,
            "destination" -> destination,
            "distance" -> totalDistance,
            "walking_time_ticks" -> walkingTimeTicks,
            "arrival_tick" -> arrivalTick,
            "walking_speed" -> walkingSpeed,
            "tick" -> currentTick
          ),
          "person_walking_start"
        )

        val updatedState = state.copy(currentTripDestinationNodeId = Some(destination))
        Some((updatedState, arrivalTick))

      case None =>
        logError(s"$personId cannot find walking route from $origin to $destination")
        GPSMetrics.gpsCannotFindRoute.labels("person_walking").inc()
        None
    }
  }

  /** Report walking trip completion.
    *
    * @param travelTime Travel time in ticks
    * @param currentTick Current simulation tick
    */
  def reportWalkingCompleted(travelTime: Long, currentTick: Tick): Unit = {
    reportFn(
      Map(
        "event_type" -> "walking_trip_completed",
        "person_id" -> personId,
        "travel_time" -> travelTime,
        "arrival_tick" -> currentTick,
        "tick" -> currentTick
      ),
      "person_walking_completed"
    )

    logDebug(s"$personId completed walking trip in ${travelTime}s")
  }
}
