package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.plan.WalkLeg
import model.hybrid.util.{ CityMapUtil, GPSUtil }
import org.interscity.htc.core.metrics.model.hybrid.GPSMetrics

import scala.collection.mutable

/** Handles walking legs for person actors.
  *
  * Responsibilities:
  *   - Calculate walking routes
  *   - Compute walking time based on distance
  *   - Report walking leg events
  *   - Handle route calculation failures
  *
  * @param personId Person actor ID
  * @param reportFn Reporting function for events
  * @param logDebug Debug logging function
  * @param logError Error logging function
  */
class PersonWalkingTripHandler(
  personId: String,
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

  /** Initiate a walking leg (mesoscopic).
    *
    * Calculates a route using the road network (or reuses `leg.precomputedRoute`), computes
    * walking time based on distance and walking speed, and returns the arrival tick.
    *
    * @param leg Walking leg to execute
    * @param currentTick Current simulation tick
    * @param logWarn Warning logging function
    * @return Some(arrivalTick) if a route was found, None if no route exists between the leg's
    *         origin and destination
    */
  def initiateWalkingTrip(
    leg: WalkLeg,
    currentTick: Tick,
    logWarn: String => Unit
  ): Option[Tick] = {
    val routeResult: Option[(Double, mutable.Queue[(String, String)])] =
      leg.precomputedRoute match {
        case Some(route) => Some((0.0, mutable.Queue(route: _*)))
        case None => GPSUtil.calcRouteCompactWalking(
            originId = leg.originNodeId,
            destinationId = leg.destinationNodeId,
            maxExpansions = Int.MaxValue
          )
      }

    routeResult match {
      case Some((_, routeQueue)) =>
        val totalDistance = calculateRouteDistance(routeQueue, logWarn)
        val walkingTimeSeconds = totalDistance / walkingSpeed
        val walkingTimeTicks = math.ceil(walkingTimeSeconds).toLong
        val arrivalTick = currentTick + walkingTimeTicks

        logDebug(
          s"$personId walking from ${leg.originNodeId} to ${leg.destinationNodeId}: " +
            s"${totalDistance.toInt}m, ${walkingTimeTicks}s, arriving at tick $arrivalTick"
        )

        reportFn(
          Map(
            "event_type" -> "walking_trip_start",
            "person_id" -> personId,
            "origin" -> leg.originNodeId,
            "destination" -> leg.destinationNodeId,
            "distance" -> totalDistance,
            "walking_time_ticks" -> walkingTimeTicks,
            "arrival_tick" -> arrivalTick,
            "walking_speed" -> walkingSpeed,
            "tick" -> currentTick
          ),
          "person_walking_start"
        )

        Some(arrivalTick)

      case None =>
        logError(s"$personId cannot find walking route from ${leg.originNodeId} to ${leg.destinationNodeId}")
        GPSMetrics.gpsCannotFindRoute.labels("person_walking").inc()
        None
    }
  }

  /** Report walking leg completion.
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

    logDebug(s"$personId completed walking leg in ${travelTime}s")
  }
}
