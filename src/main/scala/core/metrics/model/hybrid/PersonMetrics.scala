package org.interscity.htc
package core.metrics.model.hybrid

import io.prometheus.client.{Counter, Gauge, Histogram}

/** Person activity schedule and trip metrics.
 *
 *   - htc_person_complete_schedule_total — persons that completed their full daily schedule
 *   - htc_person_trip_start_total — trip starts by activity type and mode
 *   - htc_person_trip_end_total — trip ends by activity type and mode
 *   - htc_person_complete_trip_reason_total — trip completion outcomes by activity type, mode and reason
 *   - htc_person_arrival_delay_ticks — histogram of arrival delay in ticks by activity type
 *   - htc_person_delayed_arrival_total — trips where person arrived later than scheduled
 *   - htc_person_truncated_activity_total — activities removed due to endTime > simulation duration
 *   - htc_person_truncated_activity_excess_ticks — histogram of how far beyond sim duration each removed activity was
 */
object PersonMetrics {

  val completeSchedule: Counter = Counter
    .build()
    .name("htc_person_complete_schedule_total")
    .help("Total complete schedules generated for people")
    .register()

  val personTripStart: Counter = Counter
    .build()
    .name("htc_person_activity_start_total")
    .help("Total person activity start events processed, by activity type")
    .labelNames("activity_type", "mode")
    .register()

  val personTripEnd: Counter = Counter
    .build()
    .name("htc_person_activity_end_total")
    .help("Total person activity start events processed, by activity type")
    .labelNames("activity_type", "mode")
    .register()

   val personCompleteTripReason: Counter = Counter
    .build()
    .name("htc_person_complete_trip_reason_total")
    .help("Total person activity end events processed, by activity type and reason for completion")
    .labelNames("activity_type", "mode", "reason")
    .register()

  val personArrivalDelayTicks: Histogram = Histogram
    .build()
    .name("htc_person_arrival_delay_ticks")
    .help("Observed arrival delay when a person starts an activity (ticks, >= 0)")
    .labelNames("activity_type")
    .buckets(0, 1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600)
    .register()

  val personDelayedArrival: Counter = Counter
    .build()
    .name("htc_person_delayed_arrival_total")
    .help("Total delayed person arrivals (observed delay > 0), by activity type")
    .labelNames("activity_type")
    .register()

  val personTruncatedActivity: Counter = Counter
    .build()
    .name("htc_person_truncated_activity_total")
    .help("Total activities removed because endTime exceeded simulation duration, by activity type")
    .labelNames("activity_type")
    .register()

  // Buckets in ticks (seconds): 1min, 5min, 15min, 30min, 1h, 2h, 4h, 8h, 12h, 24h, 48h, +Inf
  val personTruncatedActivityExcessTicks: Histogram = Histogram
    .build()
    .name("htc_person_truncated_activity_excess_ticks")
    .help("Distribution of how many ticks beyond simulation duration each removed activity falls (endTime - duration), by activity type")
    .labelNames("activity_type")
    .buckets(60, 300, 900, 1800, 3600, 7200, 14400, 28800, 43200, 86400, 172800)
    .register()
}
