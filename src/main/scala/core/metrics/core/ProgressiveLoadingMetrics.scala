package org.interscity.htc
package core.metrics.core

import io.prometheus.client.{Counter, Gauge, Histogram}

/** Progressive loading metrics.
  *
  * ── Counters / Gauges ──
  *   - htc_progressive_actors_created_total — actors created by progressive loading
  *   - htc_progressive_loaded_up_to_tick — highest tick fully loaded
  *   - htc_progressive_windows_loaded_total — number of tick windows completed
  *
  * ── Timing ──
  *   - htc_progressive_window_load_duration_seconds — wall-clock time to load each window
  *     (from requestProgressiveLoad to TickWindowReady, covers all calls including proactive prefetch)
  *   - htc_tm_blocked_for_progressive_seconds — wall-clock time GTM was blocked mid-simulation
  *     waiting for a progressive window (i.e. waitingForProgressiveLoad = true; excludes proactive
  *     prefetches and the initial window)
  */
object ProgressiveLoadingMetrics {

  val progressiveActorsCreated: Counter = Counter
    .build()
    .name("htc_progressive_actors_created_total")
    .help("Total actors created via progressive loading")
    .register()

  val progressiveLoadedUpToTick: Gauge = Gauge
    .build()
    .name("htc_progressive_loaded_up_to_tick")
    .help("Highest tick for which all progressive actors are created and initialized")
    .register()

  val progressiveWindowsLoaded: Counter = Counter
    .build()
    .name("htc_progressive_windows_loaded_total")
    .help("Number of progressive tick windows fully loaded")
    .register()

  val windowLoadDurationSeconds: Histogram = Histogram
    .build()
    .name("htc_progressive_window_load_duration_seconds")
    .help("Wall-clock time to load a progressive tick window (from request to TickWindowReady)")
    .buckets(0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0)
    .register()

  val blockedWaitDurationSeconds: Histogram = Histogram
    .build()
    .name("htc_tm_blocked_for_progressive_seconds")
    .help("Wall-clock time GlobalTimeManager was blocked mid-simulation waiting for a progressive window")
    .buckets(0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0)
    .register()
}
