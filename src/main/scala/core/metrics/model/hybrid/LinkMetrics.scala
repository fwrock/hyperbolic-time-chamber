package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.{ Counter, Gauge, Histogram }

/** Road link flow and occupancy metrics.
  *
  * ── Flow ──
  *   - htc_link_vehicles_entered_total{mode} — vehicles that entered a link, by simulation mode (MESO | MICRO)
  *   - htc_link_vehicles_exited_total{mode} — vehicles that exited a link, by simulation mode
  *
  * ── Travel time ──
  *   - htc_link_travel_time_ticks{mode} — histogram of link traversal time in simulation ticks
  *
  * ── Occupancy ──
  *   - htc_link_vehicles_active — current vehicles on MICRO links (gauge)
  *
  * Simulation modes: MESO, MICRO
  */
object LinkMetrics {

  val vehiclesEntered: Counter = Counter
    .build()
    .name("htc_link_vehicles_entered_total")
    .help("Total vehicles that entered a link, by simulation mode")
    .labelNames("mode")
    .register()

  val vehiclesExited: Counter = Counter
    .build()
    .name("htc_link_vehicles_exited_total")
    .help("Total vehicles that exited a link, by simulation mode")
    .labelNames("mode")
    .register()

  val travelTimeTicks: Histogram = Histogram
    .build()
    .name("htc_link_travel_time_ticks")
    .help("Link traversal time in simulation ticks, by simulation mode")
    .labelNames("mode")
    .buckets(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000)
    .register()

  val vehiclesActive: Gauge = Gauge
    .build()
    .name("htc_link_vehicles_active")
    .help("Current number of vehicles occupying MICRO links")
    .register()
}
