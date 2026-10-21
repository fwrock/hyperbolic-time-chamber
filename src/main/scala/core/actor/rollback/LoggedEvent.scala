package org.interscity.htc
package core.actor.rollback

import core.types.Tick

/** One entry in a [[RollbackHistoryHandler]]'s event log: an event this actor has already
  * processed, kept around so a later rollback can either replay past it (if it survives) or
  * return it as "undone" (if it doesn't) — see `docs/TIME_WARP_DESIGN.md` §6/§7.
  *
  * @param tick
  *   the simulation tick this event was processed at
  * @param seq
  *   this actor's own monotonically increasing processed-event counter — the true ordering key
  *   for replay, since multiple events can share the same `tick` (e.g. a batch dispatch)
  * @param event
  *   the original event, kept so `replayEventFn` can re-execute it against a restored checkpoint.
  *   Typed `AnyRef`, not `core.entity.event.BaseEvent[?]` — `ActorInteractionEvent`, one of the
  *   two real event types an actor processes, isn't a `BaseEvent` subtype; see
  *   `RollbackHistoryHandler`'s doc for how this was found.
  * @param sentMessages
  *   every message this actor sent while processing this event, with enough addressing info
  *   ([[SentMessage]]) for [[AntiMessageCascade]] to turn each into an anti-message once this
  *   entry is undone. Empty until live actor wiring captures it — see `docs/TIME_WARP_DESIGN.md`'s
  *   step-5 log for why that capture (instrumenting `SimulationBaseActor.sendMessageTo`) isn't
  *   built yet even though the cascade math that consumes it already is.
  * @param dynamicWeightReads
  *   every `DynamicWeightCache.getWeight(linkId, ...)` result this actor observed while processing
  *   this event (`docs/TIME_WARP_DESIGN.md`'s model-level audit, third finding). Dynamic link costs
  *   arrive via an out-of-band, real-time pub-sub channel outside the deterministic event log — a
  *   route computed during this event's original live dispatch must see the exact same costs if
  *   this event is ever replayed, not whatever the cache holds by the time replay happens. Empty
  *   for any event that never read a dynamic weight.
  * @param scheduleOutcome
  *   what this event asked the time manager to schedule for the actor: `None` if it never asked,
  *   `Some(None)` if it finished with no next tick, `Some(Some(t))` if it asked for tick `t`. The
  *   undone event's registration is swept on rollback, so the surviving events' latest outcome is
  *   what the actor must be rescheduled at.
  */
final case class LoggedEvent(
  tick: Tick,
  seq: Long,
  event: AnyRef,
  sentMessages: Seq[SentMessage] = Seq.empty,
  dynamicWeightReads: Map[String, Double] = Map.empty,
  scheduleOutcome: Option[Option[Tick]] = None
)
