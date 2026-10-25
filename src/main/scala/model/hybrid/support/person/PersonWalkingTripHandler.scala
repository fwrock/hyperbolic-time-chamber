package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.plan.WalkLeg
import model.hybrid.entity.state.WalkingLegProgress
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
  logError: String => Unit,
  linkLengthFn: Long => Option[Double] = id => CityMapUtil.edgeLabelsById.get(id).map(_.length),
  routeFn: (Long, Long) => Option[(Double, mutable.Queue[(Long, Long)])] =
    (origin, destination) => GPSUtil.calcRouteCompactWalking(origin, destination, maxExpansions = Int.MaxValue)
) {

  private val walkingSpeed: Double = 1.4 // m/s (typical human walking speed)

  /** Calculate total route distance by summing link lengths.
    *
    * @param routeQueue Queue of (linkId, nodeId) pairs
    * @param logWarn Warning logging function
    * @return Total distance in meters
    */
  def calculateRouteDistance(routeQueue: mutable.Queue[(Long, Long)], logWarn: String => Unit): Option[Double] = {
    var totalDistance = 0.0
    val links = routeQueue.iterator
    while (links.hasNext) {
      val (linkId, _) = links.next()
      linkLengthFn(linkId).filter(length => length.isFinite && length >= 0.0) match {
        case Some(length) =>
          totalDistance += length
          if (!totalDistance.isFinite) {
            logWarn("Walking route distance overflow")
            return None
          }
        case None =>
          logWarn(s"Walking link $linkId has no valid length")
          return None
      }
    }
    Some(totalDistance)
  }

  /** Initiate a walking leg (mesoscopic).
    *
    * Calculates a route using the road network (or reuses `leg.precomputedRoute`), computes
    * walking time based on distance and walking speed, and returns both for checkpointing.
    *
    * @param leg Walking leg to execute
    * @param currentTick Current simulation tick
    * @param logWarn Warning logging function
    * @return measured distance and arrival tick, or None when the route cannot be measured
    */
  def initiateWalkingTrip(
    leg: WalkLeg,
    currentTick: Tick,
    logWarn: String => Unit
  ): Option[WalkingLegProgress] = {
    val routeResult: Option[(Double, mutable.Queue[(Long, Long)])] =
      leg.precomputedRoute match {
        case Some(route) => Some((0.0, mutable.Queue(route: _*)))
        case None => routeFn(leg.originNodeId, leg.destinationNodeId)
      }

    val measured = routeResult.flatMap { case (_, route) =>
      calculateRouteDistance(route, logWarn).flatMap { distance =>
        val duration = math.ceil(distance / walkingSpeed)
        if (!duration.isFinite || duration >= Long.MaxValue.toDouble) None
        else scala.util.Try(Math.addExact(currentTick, duration.toLong)).toOption
          .map(arrival => WalkingLegProgress(distance, arrival))
      }
    }
    measured match {
      case Some(progress) =>
        val totalDistance = progress.distance
        val arrivalTick = progress.arrivalTick
        val walkingTimeTicks = arrivalTick - currentTick

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

        Some(progress)

      case None =>
        logError(s"$personId cannot measure walking route from ${leg.originNodeId} to ${leg.destinationNodeId}")
        GPSMetrics.gpsCannotFindRoute.labels("person_walking").inc()
        None
    }
  }

  /** Report walking leg completion.
    *
    * @param travelTime Travel time in ticks
    * @param currentTick Current simulation tick
    */
  def reportWalkingCompleted(travelTime: Long, currentTick: Tick, distance: Double): Unit = {
    reportFn(
      Map(
        "event_type" -> "walking_trip_completed",
        "person_id" -> personId,
        "travel_time" -> travelTime,
        "distance" -> distance,
        "arrival_tick" -> currentTick,
        "tick" -> currentTick
      ),
      "person_walking_completed"
    )

    logDebug(s"$personId completed walking leg in ${travelTime}s")
  }
}
