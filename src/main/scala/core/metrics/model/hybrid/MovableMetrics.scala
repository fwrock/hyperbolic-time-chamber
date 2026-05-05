package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.{ Counter, Histogram }

/** Movable entity (vehicle and person) journey outcome metrics.
  *
  * ── Journey lifecycle ──
  *   - htc_journeys_started_total{vehicle_type} — journeys started
  *   - htc_journeys_completed_total{vehicle_type} — journeys completed (reached final node or not)
  *   - htc_journey_successes_total{vehicle_type} — journeys that successfully reached destination
  *   - htc_journey_failures_total{vehicle_type, reason} — journeys that did NOT reach destination
  *   - htc_movable_complete_journey_reason_total{mode, reason, reached_destination} — detailed
  *     outcome breakdown with exemplar support for sampling individual journey IDs
  *
  * ── Journey duration ──
  *   - htc_journey_duration_ticks{vehicle_type} — histogram of journey duration in simulation ticks
  *
  * Failure reasons for htc_journey_failures_total:
  *   route_calculation_failed, exception_during_route_request,
  *   null_origin_or_destination, simulation_time_exceeded, actor_destructed_before_completion
  */
object MovableMetrics {

  val journeysStarted: Counter = Counter
    .build()
    .name("htc_journeys_started_total")
    .help("Total vehicle/person journeys started")
    .labelNames("vehicle_type")
    .register()

  val journeysCompleted: Counter = Counter
    .build()
    .name("htc_journeys_completed_total")
    .help("Total vehicle/person journeys completed (arrived at destination or not)")
    .labelNames("vehicle_type")
    .register()

  val journeySuccesses: Counter = Counter
    .build()
    .name("htc_journey_successes_total")
    .help("Vehicle/person journeys that successfully reached destination")
    .labelNames("vehicle_type")
    .register()

  val journeyFailures: Counter = Counter
    .build()
    .name("htc_journey_failures_total")
    .help("Vehicle/person journeys that did NOT reach destination, by type and reason")
    .labelNames("vehicle_type", "reason")
    .register()

  val journeyCompletedReason: Counter = Counter
    .build()
    .name("htc_movable_complete_journey_reason_total")
    .help("Detailed journey outcome breakdown with exemplar support, by mode, reason and whether destination was reached")
    .labelNames("mode", "reason", "reached_destination")
    .register()

  val journeyDurationTicks: Histogram = Histogram
    .build()
    .name("htc_journey_duration_ticks")
    .help("Journey duration in simulation ticks, by vehicle type")
    .labelNames("vehicle_type")
    .buckets(1, 5, 10, 30, 60, 120, 300, 600, 1200, 3600, 7200)
    .register()

  val journeyDistanceMeters: Histogram = Histogram
    .build()
    .name("htc_journey_distance_meters")
    .help("Total distance traveled per journey in meters, by vehicle type")
    .labelNames("vehicle_type")
    .buckets(100, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000)
    .register()
}
