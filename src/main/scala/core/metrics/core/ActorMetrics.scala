package org.interscity.htc
package core.metrics.core

import io.prometheus.client.{Counter, Gauge}

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
}
