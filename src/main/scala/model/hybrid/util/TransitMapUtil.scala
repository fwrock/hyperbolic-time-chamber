package org.interscity.htc
package model.hybrid.util

import com.fasterxml.jackson.databind.{ DeserializationFeature, ObjectMapper }
import com.fasterxml.jackson.databind.`type`.CollectionType
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.interscity.htc.core.api.SimulatorSettingsRegistry
import org.interscity.htc.model.hybrid.entity.state.model.TransitStop

import java.io.{ BufferedInputStream, File, FileInputStream }
import java.util.{ List => JList }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try, Using }

/** Lightweight index of public-transport access points (bus stops, subway stations).
  *
  * Loaded from a flat JSON array file configured via `htc.mobility.transit-map-file` (config key)
  * or `HTC_MOBILITY_TRANSIT_MAP_FILE` (environment variable). When neither is set the util degrades
  * gracefully: [[isAvailable]] returns `false` and all look-ups return empty results.
  *
  * JSON format expected (a flat array — no wrapper object needed):
  * {{{
  * [
  *   {
  *     "id": "htcaid:stop;bus_stop_123",
  *     "actorId": "htcaid:busstop;123",
  *     "actorClassType": "hybrid.actor.BusStop",
  *     "nodeId": "htcaid:node;456",
  *     "latitude": -23.5505,
  *     "longitude": -46.6333,
  *     "stopType": "bus",
  *     "lines": ["line_1", "line_2"]
  *   }
  * ]
  * }}}
  *
  * Distances are computed via the haversine formula (great-circle distance), which accounts for
  * Earth's curvature and is far cheaper than a full network Dijkstra query.
  */
object TransitMapUtil {

  private lazy val mapper: ObjectMapper = {
    val m = new ObjectMapper()
    m.registerModule(DefaultScalaModule)
    m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    m
  }

  private lazy val _stops: List[TransitStop] = {
    val filePath = SimulatorSettingsRegistry
      .get("htc.mobility.transit-map-file")
      .orElse(sys.env.get("HTC_MOBILITY_TRANSIT_MAP_FILE"))
      .getOrElse("")

    if (filePath.isEmpty) {
      println(
        "[TransitMapUtil] No transit map file configured — dynamic mode choice will skip transit stops."
      )
      List.empty
    } else {
      loadStops(filePath) match {
        case Success(stops) =>
          println(s"[TransitMapUtil] Loaded ${stops.size} transit stops from $filePath.")
          stops
        case Failure(e) =>
          System.err.println(
            s"[TransitMapUtil] Failed to load transit stops from $filePath: ${e.getMessage}"
          )
          List.empty
      }
    }
  }

  /** All stops indexed by their `id`. */
  lazy val stopsById: Map[String, TransitStop] = _stops.map(s => s.id -> s).toMap

  /** All stops indexed by line label. A stop serving multiple lines appears under each line key. */
  lazy val stopsByLine: Map[String, List[TransitStop]] =
    _stops
      .flatMap(s => s.lines.map(_ -> s))
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap

  /** `true` when at least one stop was loaded successfully. */
  lazy val isAvailable: Boolean = _stops.nonEmpty

  // ── K-d tree for O(log N) spatial queries ──────────────────────────────────

  /** 2-D k-d tree over transit stops, keyed on (latitude, longitude).
    *
    * Built once per stop type at first access. Reduces `nearestStops` from O(N)
    * to O(log N + k) — critical for large PT networks (São Paulo, Toulouse).
    */
  private final class KdTree(stops: Array[TransitStop]) {

    private case class KdNode(
      stop: TransitStop,
      left: Option[KdNode],
      right: Option[KdNode]
    )

    private val root: Option[KdNode] = build(stops, depth = 0)

    private def build(arr: Array[TransitStop], depth: Int): Option[KdNode] =
      if (arr.isEmpty) None
      else {
        val axis   = depth % 2
        val sorted = if (axis == 0) arr.sortBy(_.latitude) else arr.sortBy(_.longitude)
        val mid    = sorted.length / 2
        Some(KdNode(
          stop  = sorted(mid),
          left  = build(sorted.slice(0, mid), depth + 1),
          right = build(sorted.slice(mid + 1, sorted.length), depth + 1)
        ))
      }

    def nearest(
      lat: Double,
      lon: Double,
      maxDistM: Double,
      maxCount: Int
    ): List[(TransitStop, Double)] = {
      val results = scala.collection.mutable.ArrayBuffer.empty[(TransitStop, Double)]
      search(root, lat, lon, maxDistM, results, depth = 0)
      results.sortInPlaceBy(_._2)
      results.take(maxCount).toList
    }

    private def search(
      nodeOpt: Option[KdNode],
      lat: Double,
      lon: Double,
      maxDistM: Double,
      results: scala.collection.mutable.ArrayBuffer[(TransitStop, Double)],
      depth: Int
    ): Unit = nodeOpt.foreach { node =>
      val dist = TransitMapUtil.haversineM(lat, lon, node.stop.latitude, node.stop.longitude)
      if (dist <= maxDistM) results += ((node.stop, dist))

      val axis      = depth % 2
      val nodeCoord = if (axis == 0) node.stop.latitude  else node.stop.longitude
      val qCoord    = if (axis == 0) lat                 else lon
      // Conservative linear distance along the split axis in metres.
      // For latitude: 1° ≈ 111 320 m.  For longitude: scaled by cos(lat).
      val axisDistM =
        if (axis == 0) math.abs(qCoord - nodeCoord) * 111_320.0
        else           math.abs(qCoord - nodeCoord) * 111_320.0 * math.cos(math.toRadians(lat))

      val (nearBranch, farBranch) =
        if (qCoord < nodeCoord) (node.left, node.right) else (node.right, node.left)

      search(nearBranch, lat, lon, maxDistM, results, depth + 1)
      // Prune the far branch when the split plane is farther than our search radius.
      if (axisDistM <= maxDistM)
        search(farBranch, lat, lon, maxDistM, results, depth + 1)
    }
  }

  /** One k-d tree per stop type, built lazily on first access. */
  private lazy val kdTreeByType: Map[String, KdTree] =
    _stops
      .groupBy(_.stopType)
      .view
      .mapValues(ss => new KdTree(ss.toArray))
      .toMap

  // ── Public spatial query ────────────────────────────────────────────────────

  /** Returns stops of `stopType` within `maxDistanceM` metres of `(lat, lon)`, sorted by ascending
    * distance. Returns at most `maxCount` results.
    *
    * Uses a per-type k-d tree for O(log N + k) performance instead of a full linear scan.
    *
    * @param lat          WGS-84 latitude of the query point.
    * @param lon          WGS-84 longitude of the query point.
    * @param stopType     `"bus"` or `"subway"`.
    * @param maxDistanceM Maximum haversine distance in metres.
    * @param maxCount     Upper bound on results returned (default 5).
    * @return             List of `(stop, distanceMetres)` pairs, nearest first.
    */
  def nearestStops(
    lat: Double,
    lon: Double,
    stopType: String,
    maxDistanceM: Double,
    maxCount: Int = 5
  ): List[(TransitStop, Double)] =
    kdTreeByType
      .get(stopType)
      .map(_.nearest(lat, lon, maxDistanceM, maxCount))
      .getOrElse(List.empty)

  /** Haversine great-circle distance in metres between two WGS-84 coordinates.
    *
    * $$ d = 2R \arctan2\!\left(\sqrt{a},\,\sqrt{1-a}\right) $$
    *
    * where
    * $$ a = \sin^2\!\tfrac{\Delta\phi}{2} + \cos\phi_1\cos\phi_2\sin^2\!\tfrac{\Delta\lambda}{2} $$
    */
  def haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = {
    val R    = 6_371_000.0
    val dLat = math.toRadians(lat2 - lat1)
    val dLon = math.toRadians(lon2 - lon1)
    val a =
      math.sin(dLat / 2) * math.sin(dLat / 2) +
        math.cos(math.toRadians(lat1)) * math.cos(math.toRadians(lat2)) *
          math.sin(dLon / 2) * math.sin(dLon / 2)
    R * 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
  }

  private def loadStops(filePath: String): Try[List[TransitStop]] =
    Using(new BufferedInputStream(new FileInputStream(new File(filePath)))) { stream =>
      val listType: CollectionType =
        mapper.getTypeFactory.constructCollectionType(classOf[JList[_]], classOf[TransitStop])
      val javaList: JList[TransitStop] = mapper.readValue(stream, listType)
      javaList.asScala.toList
    }
}
