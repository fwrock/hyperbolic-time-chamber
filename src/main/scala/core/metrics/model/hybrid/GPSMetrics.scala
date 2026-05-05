package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.{ Counter, Gauge, Histogram }

/** GPS routing failure and performance metrics.
  *
  * ── Route Source ──
  *   - htc_gps_route_source_total{source} — routes served by source: precomputed, preloaded, gps_calculated
  *
  * ── Failures ──
  *   - htc_gps_cannot_find_route_total{mode} — route calculation failures by vehicle/person mode
  *   - htc_gps_node_not_found_total{type} — node lookup failures by node role (origin | destination)
  *
  * ── Performance ──
  *   - htc_gps_route_calc_duration_seconds{algorithm} — wall-clock time per route calculation
  *   - htc_gps_route_hops{algorithm} — number of hops (links) in each calculated route
  *
  * ── Preprocessing ──
  *   - htc_gps_alt_precomputation_duration_seconds — total wall-clock time to build the ALT index
  *   - htc_gps_alt_landmark_count — number of landmarks used in the ALT index
  *
  * Algorithm labels: astar_dynamic, astar_pure, alt, ch_astar_static, ch_astar_adaptive
  * Source labels: precomputed, preloaded, gps_calculated
  *
  * High-cardinality identifiers (node IDs, origin/destination pairs) belong in ClickHouse reports,
  * not in Prometheus labels.
  */
object GPSMetrics {

  val routeSource: Counter = Counter
    .build()
    .name("htc_gps_route_source_total")
    .help("Total route requests served by source (precomputed, preloaded, gps_calculated)")
    .labelNames("source")
    .register()

  val gpsCannotFindRoute: Counter = Counter
    .build()
    .name("htc_gps_cannot_find_route_total")
    .help("Total route calculation failures by vehicle/person mode")
    .labelNames("mode")
    .register()

  val gpsNodeNotFound: Counter = Counter
    .build()
    .name("htc_gps_node_not_found_total")
    .help("Total GPS route requests that failed due to node not found, by node role")
    .labelNames("type")
    .register()

  val routeCalcDuration: Histogram = Histogram
    .build()
    .name("htc_gps_route_calc_duration_seconds")
    .help("Wall-clock time to calculate a route, by algorithm")
    .labelNames("algorithm")
    .buckets(0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0)
    .register()

  val routeHops: Histogram = Histogram
    .build()
    .name("htc_gps_route_hops")
    .help("Number of hops (links) in each calculated route, by algorithm")
    .labelNames("algorithm")
    .buckets(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000)
    .register()

  val altPrecomputationDuration: Gauge = Gauge
    .build()
    .name("htc_gps_alt_precomputation_duration_seconds")
    .help("Total wall-clock time in seconds to build the ALT landmark index at startup")
    .register()

  val altLandmarkCount: Gauge = Gauge
    .build()
    .name("htc_gps_alt_landmark_count")
    .help("Number of landmarks used in the ALT routing index")
    .register()
}