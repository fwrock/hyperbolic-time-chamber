package org.interscity.htc
package core.metrics.core

import io.prometheus.client.Counter

/** Raw material for the communication-amplification ratios used to compare scheduling protocols
  * (baseline vs. infrastructure-owned scheduling in `model.mobility`).
  *
  * Numerator sources: `htc_messages_sent_total` (domain traffic), `htc_events_processed_total`
  * (`finish`, `interaction`) and `htc_tm_schedule_ops_total` (schedule/replace reaching a TM).
  * Denominator: `htc_link_traversals_total`.
  *
  * {{{
  * CA_total    = sum(rate(htc_messages_sent_total)) / sum(rate(htc_link_traversals_total))
  * CA_TM       = sum(rate(htc_tm_schedule_ops_total))
  *                 / sum(rate(htc_link_traversals_total))
  * CA_link_car = sum(rate(htc_messages_sent_total{from_type="Link"})) / sum(rate(htc_link_traversals_total))
  * }}}
  */
object SchedulingMetrics {

  val scheduleOps: Counter = Counter
    .build()
    .name("htc_tm_schedule_ops_total")
    .help("Schedule requests that reached a local time manager, by operation (schedule/replace/finish/delegated)")
    .labelNames("op")
    .register()

  val schedule: Counter.Child = scheduleOps.labels("schedule")
  val replace: Counter.Child = scheduleOps.labels("replace")
  val finish: Counter.Child = scheduleOps.labels("finish")
  val delegated: Counter.Child = scheduleOps.labels("delegated")

  val linkTraversals: Counter = Counter
    .build()
    .name("htc_link_traversals_total")
    .help("Completed link traversals (vehicle left a link it was registered on), by simulation mode")
    .labelNames("mode")
    .register()

  val linkTraversalsMeso: Counter.Child = linkTraversals.labels("meso")
  val linkTraversalsMicro: Counter.Child = linkTraversals.labels("micro")
}
