package org.interscity.htc
package core.metrics.core

import io.prometheus.client.Gauge

/** Time Manager state metrics.
  *
  *   - htc_tm_scheduled_actors — actors scheduled on this TM at current tick, labeled by `manager`
  *   - htc_tm_running_events — spontaneous events currently in-flight, labeled by `manager`
  *   - htc_tm_waiting_for_progressive — 1 if GTM is blocked waiting for progressive load
  *
  * `tmScheduledActors`/`tmRunningEvents` are labeled by `manager` -- a bounded identifier (e.g. a
  * pool routee's `self.path.name`, like "$a"/"$b", or a fixed actor name for a singleton LTM), never
  * an entity id (CLAUDE.md rule 10: no unbounded label cardinality). Found unlabeled during a Time
  * Warp livelock investigation: with 16 `OptimisticLocalTimeManager` pool routees all writing the
  * same unlabeled gauge, the last writer wins and the metric can't tell you which routee (if any) is
  * actually stuck -- labeling by manager fixes that without introducing unbounded cardinality.
  */
object TimeManagerMetrics {

  val tmScheduledActors: Gauge = Gauge
    .build()
    .name("htc_tm_scheduled_actors")
    .help("Number of actors scheduled for current tick on this local TM")
    .labelNames("manager")
    .register()

  val tmRunningEvents: Gauge = Gauge
    .build()
    .name("htc_tm_running_events")
    .help("Number of spontaneous events currently in-flight on this local TM")
    .labelNames("manager")
    .register()

  val tmWaitingForProgressive: Gauge = Gauge
    .build()
    .name("htc_tm_waiting_for_progressive")
    .help("1 if GlobalTimeManager is blocked waiting for progressive load, 0 otherwise")
    .register()
}
