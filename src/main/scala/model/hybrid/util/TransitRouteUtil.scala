package org.interscity.htc
package model.hybrid.util

import com.fasterxml.jackson.databind.{ DeserializationFeature, ObjectMapper }
import com.fasterxml.jackson.databind.`type`.CollectionType
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.model.hybrid.entity.state.model.TransitRoute

import java.io.{ BufferedInputStream, File, FileInputStream }
import java.util.{ List => JList }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try, Using }

/** Index of transit routes for frequency-based RAPTOR routing.
  *
  * Loaded from a flat JSON array file configured via `htc.mobility.transit-routes-file` (config
  * key) or `HTC_MOBILITY_TRANSIT_ROUTES_FILE` (environment variable). When neither is set the util
  * degrades gracefully: [[isAvailable]] returns `false` and RAPTOR falls back to the standard
  * single-leg mode choice.
  *
  * JSON format (flat array in `transit_routes.json`):
  * {{{
  * [
  *   {
  *     "lineId": "1012-10",
  *     "stopType": "bus",
  *     "headwaySeconds": 600,
  *     "stops": [
  *       { "stopId": "htcaid:stop;bus_sptrans_301790", "travelTimeFromPrevSeconds": 0 },
  *       { "stopId": "htcaid:stop;bus_sptrans_301764", "travelTimeFromPrevSeconds": 80 }
  *     ]
  *   }
  * ]
  * }}}
  *
  * The `lineId` must match the line labels stored in `TransitStop.lines` (transit_map.json).
  */
object TransitRouteUtil {

  private lazy val mapper: ObjectMapper = {
    val m = new ObjectMapper()
    m.registerModule(DefaultScalaModule)
    m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    m
  }

  private lazy val _routes: List[TransitRoute] = {
    val filePath = SimulatorSettingsRegistry
      .get("htc.mobility.transit-routes-file")
      .orElse(sys.env.get("HTC_MOBILITY_TRANSIT_ROUTES_FILE"))
      .getOrElse("")

    if (filePath.isEmpty) {
      println(
        "[TransitRouteUtil] No transit routes file configured — RAPTOR multi-leg routing disabled."
      )
      List.empty
    } else {
      loadRoutes(filePath) match {
        case Success(routes) =>
          println(s"[TransitRouteUtil] Loaded ${routes.size} transit routes from $filePath.")
          routes
        case Failure(e) =>
          System.err.println(
            s"[TransitRouteUtil] Failed to load transit routes from $filePath: ${e.getMessage}"
          )
          List.empty
      }
    }
  }

  /** All routes indexed by their `lineId`. */
  lazy val routesByLine: Map[String, TransitRoute] = _routes.map(r => r.lineId -> r).toMap

  /** For each stop ID, the list of `(route, stopIndexInRoute)` pairs that serve it.
    *
    * This is the core index used by the RAPTOR algorithm to find which routes to board at any
    * given stop. A stop appearing at position `idx` in a route is indexed under `route.stops(idx).stopId`.
    */
  lazy val routesServingStop: Map[String, List[(TransitRoute, Int)]] =
    _routes
      .flatMap { route =>
        route.stops.zipWithIndex.map { case (stop, idx) =>
          stop.stopId -> (route, idx)
        }
      }
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap

  /** Cumulative travel time (seconds) from the first stop to each stop in the route.
    *
    * `cumulativeTime(lineId)(i)` = total scheduled travel time from stop 0 to stop i.
    * Used by RAPTOR to compute leg travel time without re-summing every time.
    */
  lazy val cumulativeTime: Map[String, Array[Int]] =
    _routes.map { route =>
      val arr = new Array[Int](route.stops.size)
      var acc = 0
      for (i <- route.stops.indices) {
        acc += route.stops(i).travelTimeFromPrevSeconds
        arr(i) = acc
      }
      route.lineId -> arr
    }.toMap

  /** `true` when at least one route was loaded successfully. */
  lazy val isAvailable: Boolean = _routes.nonEmpty

  /** Stop IDs a rider can actually reach by staying aboard `route` from `boardingStopId`, in the
    * direction `route.stops` is ordered (first terminal to last terminal — see [[TransitRoute]]'s
    * own doc). `None` when `boardingStopId` doesn't appear on `route` at all — directionality
    * can't be verified in that case, distinct from `Some(Set.empty)` (boarding stop found but is
    * the route's last stop, so genuinely nothing is reachable from it).
    *
    * Pure — no singleton/file reads — so callers can unit-test the direction logic itself with
    * synthetic `TransitRoute` values, and this repo's single-leg mode-choice heuristics
    * (`TravelTimeModeChoiceStrategy.bestAlightingStop`, `ModeChoiceUtil.bestAlightingStop`) use it
    * to stop offering an alighting stop the line's real scheduled service never reaches from the
    * chosen boarding stop — the class of bug found via `htc-scenario-qa`'s hybrid smoke-test
    * scenario (2026-07-23): those two heuristics used to rank every stop sharing a `line` label
    * purely by haversine distance to the destination, with no notion of the line's actual
    * direction of travel, so they could (and did) offer a PT trip no vehicle ever fulfills —
    * silently stranding the rider forever, no exception. [[RaptorRouter]] (used by the `"raptor"`
    * engine) never had this defect; it already treats `transit_routes.json`'s stop order as
    * authoritative.
    */
  def reachableStopIdsAfter(route: TransitRoute, boardingStopId: String): Option[Set[String]] = {
    val boardIdx = route.stops.indexWhere(_.stopId == boardingStopId)
    if (boardIdx < 0) None
    else Some(route.stops.drop(boardIdx + 1).map(_.stopId).toSet)
  }

  private def loadRoutes(filePath: String): Try[List[TransitRoute]] =
    Using(new BufferedInputStream(new FileInputStream(new File(filePath)))) { stream =>
      val listType: CollectionType =
        mapper.getTypeFactory.constructCollectionType(classOf[JList[_]], classOf[TransitRoute])
      val javaList: JList[TransitRoute] = mapper.readValue(stream, listType)
      javaList.asScala.toList
    }
}
