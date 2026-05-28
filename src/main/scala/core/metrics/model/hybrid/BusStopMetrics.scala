package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.{ Counter, Gauge }

/** Bus stop passenger flow metrics.
  *
  * ── Passenger flow ──
  *   - htc_bus_stop_passengers_arrived_total{label}  — total passengers arrived at bus stops
  *   - htc_bus_stop_passengers_loaded_total{label}   — total passengers loaded onto buses
  *   - htc_bus_stop_passengers_waiting               — gauge: passengers currently waiting
  */
object BusStopMetrics {

  val passengersArrived: Counter = Counter
    .build()
    .name("htc_bus_stop_passengers_arrived_total")
    .help("Total passengers arrived at bus stops, by route label")
    .labelNames("label")
    .register()

  val passengersLoaded: Counter = Counter
    .build()
    .name("htc_bus_stop_passengers_loaded_total")
    .help("Total passengers loaded onto buses from bus stops, by route label")
    .labelNames("label")
    .register()

  val passengersWaiting: Gauge = Gauge
    .build()
    .name("htc_bus_stop_passengers_waiting")
    .help("Passengers currently waiting at bus stops (all stops combined)")
    .register()
}
