package org.interscity.htc
package core.metrics.core

import io.prometheus.client.{Counter, Gauge, Histogram}

/** Simulation progress and tick metrics.
  *
  *   - htc_simulation_ticks_total — global tick counter
  *   - htc_simulation_current_tick — current global tick gauge
  *   - htc_simulation_progress_ratio — progress toward configured duration [0,1]
  *   - htc_simulation_configured_duration_ticks — configured total simulation duration
  *   - htc_tick_duration_seconds — histogram of global tick processing time
  */
object SimulationMetrics {

  val simulationTicks: Counter = Counter
    .build()
    .name("htc_simulation_ticks_total")
    .help("Total number of global simulation ticks processed")
    .register()

  val currentTick: Gauge = Gauge
    .build()
    .name("htc_simulation_current_tick")
    .help("Current global simulation tick")
    .register()

  val simulationProgress: Gauge = Gauge
    .build()
    .name("htc_simulation_progress_ratio")
    .help("Simulation progress as ratio of current tick to configured duration [0..1]")
    .register()

  val tickDuration: Histogram = Histogram
    .build()
    .name("htc_tick_duration_seconds")
    .help("Duration of each global tick cycle in seconds (from all-TMs-reported to next broadcast)")
    .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
    .register()

  val configuredDuration: Gauge = Gauge
    .build()
    .name("htc_simulation_configured_duration_ticks")
    .help("Configured total simulation duration in ticks")
    .register()
}
