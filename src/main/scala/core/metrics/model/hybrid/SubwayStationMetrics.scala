package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.{ Counter, Gauge }

/** Subway station vehicle creation and passenger flow metrics.
  *
  * ── Subway creation ──
  *   - htc_subway_station_subways_created_total{line}       — total subway trains created by line
  *
  * ── Passenger flow ──
  *   - htc_subway_station_passengers_arrived_total{line}    — total passengers arrived at stations
  *   - htc_subway_station_passengers_boarded_total{line}    — total passengers boarded onto trains
  *   - htc_subway_station_passengers_waiting{line}          — gauge: passengers currently waiting
  */
object SubwayStationMetrics {

  val subwaysCreated: Counter = Counter
    .build()
    .name("htc_subway_station_subways_created_total")
    .help("Total subway trains created by subway stations, by line")
    .labelNames("line")
    .register()

  val passengersArrived: Counter = Counter
    .build()
    .name("htc_subway_station_passengers_arrived_total")
    .help("Total passengers arrived at subway stations, by line")
    .labelNames("line")
    .register()

  val passengersBoarded: Counter = Counter
    .build()
    .name("htc_subway_station_passengers_boarded_total")
    .help("Total passengers boarded onto subway trains at stations, by line")
    .labelNames("line")
    .register()

  val passengersWaiting: Gauge = Gauge
    .build()
    .name("htc_subway_station_passengers_waiting")
    .help("Passengers currently waiting at subway stations, by line")
    .labelNames("line")
    .register()
}
