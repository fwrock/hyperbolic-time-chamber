package org.interscity.htc
package core.metrics.core

import io.prometheus.client.{Counter, Gauge, Histogram}

/** Actor lifecycle and inter-actor communication metrics.
  *
  *   - htc_actors_registered_total — cumulative actors registered on TMs
  *   - htc_actors_active — gauge of active actors by type
  *   - htc_actors_initialized_total — cumulative actors successfully initialized (state loaded), by type
  *   - htc_actors_destroyed_total — cumulative actors destroyed (DestructEvent), by type
  *   - htc_events_processed_total — spontaneous/interaction/finish/destruct events processed
  *   - htc_messages_sent_total — inter-actor messages sent by sender type and event type
  */
object ActorMetrics {

  val actorsRegistered: Counter = Counter
    .build()
    .name("htc_actors_registered_total")
    .help("Total actors registered on local time managers")
    .labelNames("actor_type")
    .register()

  val activeActors: Gauge = Gauge
    .build()
    .name("htc_actors_active")
    .help("Number of active actors by type")
    .labelNames("actor_type")
    .register()

  val actorsDestroyed: Counter = Counter
    .build()
    .name("htc_actors_destroyed_total")
    .help("Total actors destroyed via DestructEvent, by actor type")
    .labelNames("actor_type")
    .register()

  val actorsInitialized: Counter = Counter
    .build()
    .name("htc_actors_initialized_total")
    .help("Total actors successfully initialized (state loaded from InitializeEvent), by actor type")
    .labelNames("actor_type")
    .register()

  val eventsProcessed: Counter = Counter
    .build()
    .name("htc_events_processed_total")
    .help("Total spontaneous events dispatched by local time managers")
    .labelNames("event_type")
    .register()

  val messagesSent: Counter = Counter
    .build()
    .name("htc_messages_sent_total")
    .help("Total inter-actor messages sent, by sender type and event type")
    .labelNames("from_type", "event_type")
    .register()

  val eventsWhenStateIsNull: Counter = Counter
    .build()
    .name("htc_events_when_state_is_null_total")
    .help("Total spontaneous events processed when actor state is null, by actor type and event type")
    .labelNames("actor_type", "event_type")
    .register()

  val spontaneousEventAfterCompletion: Counter = Counter
    .build()
    .name("htc_spontaneous_event_after_completion_total")
    .help("Total spontaneous events processed after actor completion, by actor type and event type")
    .labelNames("actor_type", "event_type")
    .register()

  /** Time Warp report-pipeline visibility (found 2026-09-11, docs/KNOWN_GAPS.md's "Report
    * loss/duplication" entry): distinguishes *why* a report() call never reaches a reporter target
    * under Time Warp -- discarded by a rollback that undid the tick it described, vs. genuinely
    * flushed once GVT (or a destruct) confirmed it safe. Compare against `eventsProcessed`'s same
    * `event_type` label, which counts every report() call attempted (both modes): if
    * `attempted ≈ discarded + flushed`, the loss is fully explained by rollback discard (buffering
    * bug); if `discarded + flushed` is well below `attempted`, reports are being lost somewhere
    * else entirely (a live rollback storm ending mid-flight, buffer never drained, etc.).
    */
  val reportDiscardedByRollback: Counter = Counter
    .build()
    .name("htc_report_discarded_by_rollback_total")
    .help("Total buffered report() events discarded because a Time Warp rollback undid their tick, by event type")
    .labelNames("event_type")
    .register()

  val reportFlushed: Counter = Counter
    .build()
    .name("htc_report_flushed_total")
    .help("Total report() events actually sent to a reporter target (immediately in conservative mode, or once GVT/destruct confirmed them safe under Time Warp), by event type")
    .labelNames("event_type")
    .register()

  /** Aggregate anti-message volume by sender actor type (never by entity id -- see this file's own
    * cardinality rule). Correlate against `reportDiscardedByRollback`/`reportFlushed` at the same
    * `actor_type` to see whether report loss for a given actor type tracks its rollback volume.
    */
  val antiMessagesSent: Counter = Counter
    .build()
    .name("htc_antimessages_sent_total")
    .help("Total anti-messages sent by a Time Warp rollback cascade, by sender actor type")
    .labelNames("sender_actor_type")
    .register()

  /** Root-cause visibility for Time Warp rollback churn (found 2026-09-11, docs/KNOWN_GAPS.md's
    * "Report loss/duplication" entry, Cause 3): the previous report-pipeline metrics above showed
    * the buffering/discard accounting was correct, but not *why* so much gets discarded in the
    * first place. These three answer that: which (receiver, sender, message) triples cause a
    * genuine causality violation (`handleInteractWith`'s `event.tick < currentTick` branch --
    * NOT the anti-message-cascade-continuation branch, counted separately below), and how far
    * ahead of the causally-correct point the receiver had incorrectly raced before being corrected.
    * A `stragglerRollbackDistanceTicks` distribution concentrated at small values means actors
    * mostly self-correct quickly; concentrated at large values (hundreds/thousands of ticks) means
    * the per-actor optimistic-execution granularity lets causally-coupled actors (e.g. a `Car` and
    * the `Link` it's on) drift far apart in simulated time before anything catches the mismatch --
    * worth knowing before assuming this is a fixable code bug rather than a granularity/coupling
    * question about the model itself. Intentionally verbose (100% of stragglers, not sampled) for
    * this diagnostic pass; safe to remove or downgrade to sampling once the investigation concludes.
    */
  val stragglerRollbackTriggered: Counter = Counter
    .build()
    .name("htc_straggler_rollback_triggered_total")
    .help("Total genuine causality-violation rollbacks (not anti-message cascade continuations), by receiver actor type, sender actor type, and the interaction event type that triggered it")
    .labelNames("receiver_actor_type", "sender_actor_type", "event_type")
    .register()

  val stragglerRollbackDistanceTicks: Histogram = Histogram
    .build()
    .name("htc_straggler_rollback_distance_ticks")
    .help("How many ticks ahead of the causally-correct point the receiver had raced before a genuine straggler rollback corrected it, by receiver actor type")
    .labelNames("receiver_actor_type")
    .buckets(1, 5, 10, 30, 100, 300, 1000, 3000, 10000)
    .register()

  val antiMessageRollbackTriggered: Counter = Counter
    .build()
    .name("htc_antimessage_rollback_triggered_total")
    .help("Total rollbacks triggered by receiving an anti-message (cascade continuations of a rollback rooted elsewhere), by receiver actor type")
    .labelNames("receiver_actor_type")
    .register()

  /** Counts the fix for Cause 3 (docs/KNOWN_GAPS.md, found/fixed 2026-09-11): a tick-behind
    * interaction event that `SimulationBaseActor.isKnownSupersededReply` proved is a reply to an
    * already-superseded request, dropped without paying a rollback's cost. Compare against
    * `stragglerRollbackTriggered`'s same label set to see how much rollback volume this avoided,
    * per (receiver, sender, event_type) triple.
    */
  val staleReplyDroppedWithoutRollback: Counter = Counter
    .build()
    .name("htc_stale_reply_dropped_without_rollback_total")
    .help("Total tick-behind interaction events recognized as replies to an already-superseded request and dropped without a rollback, by receiver actor type, sender actor type, and event type")
    .labelNames("receiver_actor_type", "sender_actor_type", "event_type")
    .register()
}
