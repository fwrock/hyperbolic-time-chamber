package org.interscity.htc
package model.hybrid.util

import org.interscity.htc.model.hybrid.entity.state.model.{ TransitRoute, TransitStop }

import scala.collection.mutable

/** Frequency-based RAPTOR (Round-Based Public Transit Optimised Router) for multi-leg transit
  * routing.
  *
  * Finds the minimum-travel-time transit path between an origin and destination using frequency
  * headways instead of fixed timetables. Expected wait at any boarding stop is `headwaySeconds / 2`.
  *
  * === Algorithm ===
  *
  * Standard round-based RAPTOR (Delling et al. 2012) adapted for frequency-based networks:
  *
  *   1. Initialise `τ[s]` (earliest arrival at stop `s`) to `∞` for all stops, then set `τ[s]` for
  *      each access stop near origin to `walkTime + headway/2`.
  *   2. For each round `k = 1 … maxRounds`:
  *      - Collect all routes that serve at least one ''marked'' (improved in previous round) stop.
  *      - Scan each collected route forward; maintain a ''boarding cursor'' and update `τ[p]` for
  *        every reachable subsequent stop.
  *      - Footpath relaxation: for each newly marked stop, try walking to nearby stops within
  *        `maxTransferWalkM` metres.
  *   3. Find the destination stop with the earliest `τ[s] + egressWalk`. Reconstruct the path via
  *      parent pointers.
  *
  * === Usage ===
  *
  * {{{
  * val result: Option[RaptorResult] = RaptorRouter.route(
  *   originLat         = -23.55,
  *   originLon         = -46.63,
  *   destLat           = -23.60,
  *   destLon           = -46.70,
  *   includedStopTypes = Set("bus"),
  *   maxAccessDistM    = 500.0,
  *   maxTransferWalkM  = 400.0,
  *   maxRounds         = 2
  * )
  * }}}
  *
  * @see
  *   [[TransitMapUtil]] for stop lookup.
  * @see
  *   [[TransitRouteUtil]] for route sequences and headways.
  */
object RaptorRouter {

  private val INF: Int          = Int.MaxValue / 2
  private val WALK_SPEED_MS     = 1.4  // m/s — typical pedestrian speed
  private val MAX_ACCESS_STOPS  = 5    // candidate boarding stops near origin / destination

  /** One transit leg: board one line at `boardingStop`, alight at `alightingStop`. */
  case class RaptorLeg(
    boardingStop: TransitStop,
    line: String,
    alightingStop: TransitStop,
    /** In-vehicle travel time in seconds (excludes wait at boarding stop). */
    travelTimeSecs: Int,
    /** Expected wait at boarding stop (= headway / 2). */
    waitSecs: Int,
    /** `"bus"` or `"subway"`. */
    mode: String
  )

  /** Full routing result returned when a feasible path exists. */
  case class RaptorResult(
    legs: List[RaptorLeg],
    /** Sum of access walk + all waits + all in-vehicle times + egress walk. */
    totalTimeSecs: Int,
    accessTimeSecs: Int,
    egressTimeSecs: Int
  ) {
    def transfers: Int = math.max(0, legs.size - 1)
  }

  // ── parent-pointer entry ─────────────────────────────────────────────────────

  private case class Parent(
    boardedAtStopId: String,
    lineId: String,
    /** τ value at the boarding stop at the moment of boarding (= τ[boardedAt] + headway/2). */
    boardingTimeSecs: Int
  )

  // ─────────────────────────────────────────────────────────────────────────────

  /** Find the minimum-time transit route from `(originLat, originLon)` to `(destLat, destLon)`.
    *
    * Returns `None` when:
    *   - [[TransitRouteUtil]] or [[TransitMapUtil]] is not loaded.
    *   - No transit stops exist within `maxAccessDistM` metres of either origin or destination.
    *   - No feasible path is found within `maxRounds` rounds.
    *
    * @param originLat        WGS-84 latitude of trip origin.
    * @param originLon        WGS-84 longitude of trip origin.
    * @param destLat          WGS-84 latitude of trip destination.
    * @param destLon          WGS-84 longitude of trip destination.
    * @param includedStopTypes Set of stop type strings to consider, e.g. `Set("bus", "subway")`.
    * @param maxAccessDistM   Maximum walk distance (metres) from origin/destination to a stop.
    * @param maxTransferWalkM Maximum walk distance (metres) for footpath transfers between stops.
    * @param maxRounds        Number of RAPTOR rounds (= maximum number of route boardings).
    *                         `maxRounds = 1` means direct service only; `2` allows one transfer, etc.
    */
  def route(
    originLat: Double,
    originLon: Double,
    destLat: Double,
    destLon: Double,
    includedStopTypes: Set[String],
    maxAccessDistM: Double,
    maxTransferWalkM: Double,
    maxRounds: Int = 3
  ): Option[RaptorResult] = {

    if (!TransitRouteUtil.isAvailable || !TransitMapUtil.isAvailable) return None

    // ── Access stops (origin → boarding candidates) ────────────────────────

    val accessStops: List[(TransitStop, Double)] =
      includedStopTypes.toList.flatMap { st =>
        TransitMapUtil.nearestStops(originLat, originLon, st, maxAccessDistM, MAX_ACCESS_STOPS)
      }
    if (accessStops.isEmpty) return None

    // ── Egress stops (alighting candidates → destination) ─────────────────

    val egressStops: List[(TransitStop, Double)] =
      includedStopTypes.toList.flatMap { st =>
        TransitMapUtil.nearestStops(destLat, destLon, st, maxAccessDistM, MAX_ACCESS_STOPS)
      }
    if (egressStops.isEmpty) return None

    // ── Initialise τ and parent maps ───────────────────────────────────────

    val tau    = mutable.Map[String, Int]().withDefaultValue(INF)
    val parent = mutable.Map[String, Parent]()

    // Seed: for each access stop, τ = walkTime(origin→stop) + expected wait for first vehicle.
    // We use headway/2 as the expected wait. If multiple routes serve the stop, take the minimum.
    val marked = mutable.Set[String]()

    for ((stop, distM) <- accessStops) {
      val walkSecs  = (distM / WALK_SPEED_MS).toInt
      val minHeadway = TransitRouteUtil.routesServingStop
        .getOrElse(stop.id, Nil)
        .map { case (route, _) => route.headwaySeconds }
        .minOption
        .getOrElse(600)
      val arrivalAtStop = walkSecs + minHeadway / 2
      if (arrivalAtStop < tau(stop.id)) {
        tau(stop.id) = arrivalAtStop
        marked += stop.id
      }
    }

    // ── RAPTOR rounds ──────────────────────────────────────────────────────

    for (_ <- 1 to maxRounds) {
      if (marked.isEmpty) {
        // No more stops to expand — terminate early.
      } else {
        val newlyMarked = mutable.Set[String]()

        // Collect all routes that serve at least one marked stop.
        val routesToProcess: Set[TransitRoute] = marked.flatMap { stopId =>
          TransitRouteUtil.routesServingStop.getOrElse(stopId, Nil).map(_._1)
        }.toSet

        // Scan each route forward.
        for (route <- routesToProcess) {
          val cumTimes = TransitRouteUtil.cumulativeTime.getOrElse(route.lineId, Array.empty[Int])
          if (cumTimes.nonEmpty) {
            var boardingTimeSecs: Int    = INF  // boarding time at the cursor stop
            var boardedAtStopId: String  = ""   // cursor: stop where we boarded
            var boardedAtIdx: Int        = -1   // index in route where we boarded

            for ((routeStop, idx) <- route.stops.zipWithIndex) {
              val stopId = routeStop.stopId

              // Try to improve τ[stopId] using the active boarding cursor.
              if (boardingTimeSecs < INF) {
                val travelFromBoarding = cumTimes(idx) - cumTimes(boardedAtIdx)
                val arrival            = boardingTimeSecs + travelFromBoarding
                if (arrival < tau(stopId)) {
                  tau(stopId) = arrival
                  parent(stopId) = Parent(boardedAtStopId, route.lineId, boardingTimeSecs)
                  newlyMarked += stopId
                }
              }

              // Can we board at this stop earlier than the current boarding cursor?
              val candidateBoard = tau(stopId)
              if (candidateBoard < INF) {
                val boardTime = candidateBoard + route.headwaySeconds / 2
                if (boardTime < boardingTimeSecs) {
                  boardingTimeSecs = boardTime
                  boardedAtStopId  = stopId
                  boardedAtIdx     = idx
                }
              }
            }
          }
        }

        // Footpath relaxation: walk from newly marked stops to nearby stops.
        for (stopId <- newlyMarked.toList) {
          TransitMapUtil.stopsById.get(stopId).foreach { stop =>
            includedStopTypes.foreach { st =>
              val nearbyStops = TransitMapUtil.nearestStops(
                stop.latitude, stop.longitude, st, maxTransferWalkM, MAX_ACCESS_STOPS
              )
              for ((nearStop, distM) <- nearbyStops if nearStop.id != stopId) {
                val walkSecs      = (distM / WALK_SPEED_MS).toInt
                val transferArrival = tau(stopId) + walkSecs
                if (transferArrival < tau(nearStop.id)) {
                  tau(nearStop.id) = transferArrival
                  newlyMarked += nearStop.id
                }
              }
            }
          }
        }

        marked.clear()
        marked ++= newlyMarked
      }
    }

    // ── Find best egress stop ──────────────────────────────────────────────

    val bestEgress: Option[(TransitStop, Double)] = egressStops
      .filter { case (stop, _) => tau(stop.id) < INF }
      .minByOption { case (stop, distM) =>
        val egressSecs = (distM / WALK_SPEED_MS).toInt
        tau(stop.id) + egressSecs
      }

    bestEgress.flatMap { case (destStop, egressDistM) =>
      val egressSecs = (egressDistM / WALK_SPEED_MS).toInt
      val legs       = reconstructPath(destStop.id, parent, tau)
      if (legs.isEmpty) None
      else {
        val accessSecs = accessStops
          .minByOption { case (stop, _) =>
            tau.getOrElse(stop.id, INF)  // stop that actually contributed to the path
          }
          .map { case (_, distM) => (distM / WALK_SPEED_MS).toInt }
          .getOrElse(0)

        Some(
          RaptorResult(
            legs           = legs,
            totalTimeSecs  = tau(destStop.id) + egressSecs,
            accessTimeSecs = accessSecs,
            egressTimeSecs = egressSecs
          )
        )
      }
    }
  }

  // ── Path reconstruction ──────────────────────────────────────────────────

  private def reconstructPath(
    destStopId: String,
    parent: mutable.Map[String, Parent],
    tau: mutable.Map[String, Int]
  ): List[RaptorLeg] = {
    val legs    = mutable.ArrayBuffer[RaptorLeg]()
    var current = destStopId
    var safety  = 0

    while (parent.contains(current) && safety < 20) {
      safety += 1
      val p           = parent(current)
      val boardStop   = TransitMapUtil.stopsById.get(p.boardedAtStopId)
      val alightStop  = TransitMapUtil.stopsById.get(current)
      val route       = TransitRouteUtil.routesByLine.get(p.lineId)

      (boardStop, alightStop, route) match {
        case (Some(boarding), Some(alighting), Some(r)) =>
          val travelSecs = tau.getOrElse(current, 0) - p.boardingTimeSecs
          legs.prepend(
            RaptorLeg(
              boardingStop   = boarding,
              line           = r.lineId,
              alightingStop  = alighting,
              travelTimeSecs = math.max(0, travelSecs),
              waitSecs       = r.headwaySeconds / 2,
              mode           = r.stopType
            )
          )
          current = p.boardedAtStopId

        case _ =>
          // Walk-transfer segment — skip leg, trace back through parent further.
          current = p.boardedAtStopId
      }
    }

    legs.toList
  }
}
