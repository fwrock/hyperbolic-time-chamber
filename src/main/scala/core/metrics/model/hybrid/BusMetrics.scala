package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.{ Counter, Gauge }

/** Bus passenger flow metrics.
  *
  * ── Passenger flow ──
  *   - htc_bus_passengers_boarded_total{label}   — total passengers boarded onto buses by route
  *   - htc_bus_passengers_alighted_total{label}  — total passengers alighted from buses by route
  *   - htc_bus_active_passengers                 — gauge: passengers currently on all buses
  */
object BusMetrics {

  val passengersBoarded: Counter = Counter
    .build()
    .name("htc_bus_passengers_boarded_total")
    .help("Total passengers boarded onto buses, by route label")
    .labelNames("label")
    .register()

  val passengersAlighted: Counter = Counter
    .build()
    .name("htc_bus_passengers_alighted_total")
    .help("Total passengers alighted from buses, by route label")
    .labelNames("label")
    .register()

  val activePassengers: Gauge = Gauge
    .build()
    .name("htc_bus_active_passengers")
    .help("Passengers currently riding buses (all buses combined)")
    .register()
}
