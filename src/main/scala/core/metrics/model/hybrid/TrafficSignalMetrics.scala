package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.Counter

/** Traffic signal phase change metrics.
  *
  * ── Phase changes ──
  *   - htc_traffic_signal_phase_changes_total{to_phase} — total phase transitions by target phase (Green | Red)
  */
object TrafficSignalMetrics {

  val phaseChanges: Counter = Counter
    .build()
    .name("htc_traffic_signal_phase_changes_total")
    .help("Total traffic signal phase transitions, by target phase")
    .labelNames("to_phase")
    .register()
}
