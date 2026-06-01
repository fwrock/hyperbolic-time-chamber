package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.Counter

/** Bus station vehicle creation metrics.
  *
  * ── Bus creation ──
  *   - htc_bus_station_buses_created_total{label}          — total buses successfully created
  *   - htc_bus_station_buses_skipped_no_route_total{label} — total buses skipped (no valid route)
  */
object BusStationMetrics {

  val busesCreated: Counter = Counter
    .build()
    .name("htc_bus_station_buses_created_total")
    .help("Total buses successfully created by bus stations, by route label")
    .labelNames("label")
    .register()

  val busesSkippedNoRoute: Counter = Counter
    .build()
    .name("htc_bus_station_buses_skipped_no_route_total")
    .help("Total buses skipped by bus stations due to missing or empty route, by route label")
    .labelNames("label")
    .register()
}
