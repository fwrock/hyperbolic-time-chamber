package org.interscity.htc
package core.metrics.core

import io.prometheus.client.Gauge

/** Time Manager state metrics.
  *
  *   - htc_tm_scheduled_actors — actors scheduled on this TM at current tick
  *   - htc_tm_running_events — spontaneous events currently in-flight
  *   - htc_tm_waiting_for_progressive — 1 if GTM is blocked waiting for progressive load
  */
object TimeManagerMetrics {

  val tmScheduledActors: Gauge = Gauge
    .build()
    .name("htc_tm_scheduled_actors")
    .help("Number of actors scheduled for current tick on this local TM")
    .register()

  val tmRunningEvents: Gauge = Gauge
    .build()
    .name("htc_tm_running_events")
    .help("Number of spontaneous events currently in-flight on this local TM")
    .register()

  val tmWaitingForProgressive: Gauge = Gauge
    .build()
    .name("htc_tm_waiting_for_progressive")
    .help("1 if GlobalTimeManager is blocked waiting for progressive load, 0 otherwise")
    .register()
}
