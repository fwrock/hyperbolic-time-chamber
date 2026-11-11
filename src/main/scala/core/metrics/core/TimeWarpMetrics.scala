package org.interscity.htc
package core.metrics.core

import io.prometheus.client.{CollectorRegistry, Counter, Gauge}

class TimeWarpMetricSet(registry: CollectorRegistry) {
  private def gauge(name: String, help: String): Gauge = Gauge.build().name(name).help(help).register(registry)
  val enabled = gauge("htc_time_warp_enabled", "One when the optimistic runtime is selected")
  val gvt = gauge("htc_time_warp_gvt", "Committed GVT frontier; exhausted input is represented as horizon plus one")
  val workers = gauge("htc_time_warp_workers", "Number of workers in the frozen optimistic membership")
  val commits = Counter.build().name("htc_time_warp_commits_total").help("Completed optimistic commit and retirement rounds").register(registry)
  val checkpoints = gauge("htc_time_warp_retained_checkpoints", "Checkpoints retained at the latest quiescent snapshot")
  val events = gauge("htc_time_warp_retained_events", "Events retained at the latest quiescent snapshot")
  val reports = gauge("htc_time_warp_retained_reports", "Reports retained at the latest quiescent snapshot")
  val speculativeEvents = gauge("htc_time_warp_speculative_events", "Uncommitted speculative events at the latest quiescent snapshot")
}

object TimeWarpMetrics extends TimeWarpMetricSet(CollectorRegistry.defaultRegistry)
