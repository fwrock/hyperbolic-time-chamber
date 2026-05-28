package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.{ Counter, Gauge }

/** Subway passenger flow and journey metrics.
  *
  * ── Journeys ──
  *   - htc_subway_journeys_started_total{line}   — total subway trains started by line
  *
  * ── Passenger flow ──
  *   - htc_subway_passengers_boarded_total{line} — total passengers boarded onto subways by line
  *   - htc_subway_passengers_alighted_total{line}— total passengers alighted from subways by line
  *   - htc_subway_active_passengers              — gauge: passengers currently on all subways
  */
object SubwayMetrics {

  val journeysStarted: Counter = Counter
    .build()
    .name("htc_subway_journeys_started_total")
    .help("Total subway train journeys started, by line")
    .labelNames("line")
    .register()

  val passengersBoarded: Counter = Counter
    .build()
    .name("htc_subway_passengers_boarded_total")
    .help("Total passengers boarded onto subways, by line")
    .labelNames("line")
    .register()

  val passengersAlighted: Counter = Counter
    .build()
    .name("htc_subway_passengers_alighted_total")
    .help("Total passengers alighted from subways, by line")
    .labelNames("line")
    .register()

  val activePassengers: Gauge = Gauge
    .build()
    .name("htc_subway_active_passengers")
    .help("Passengers currently riding subways (all trains combined)")
    .register()
}
