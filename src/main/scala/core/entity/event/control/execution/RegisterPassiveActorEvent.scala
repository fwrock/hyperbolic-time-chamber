package org.interscity.htc
package core.entity.event.control.execution

import org.htc.protobuf.core.entity.actor.Identify

/** Sent by a Time Warp actor whose scenario data has `scheduleOnTimeManager = false` (the common
  * case for infrastructure actors — `Link`/`Node`/`RailLink`/`BusStop`/etc. — which are purely
  * reactive and never need a periodic `SpontaneousEvent`). Found comparing conservative-vs-optimistic
  * output on a real run (`docs/TIME_WARP_DESIGN.md`): such an actor never registers with any time
  * manager at all under the existing [[RegisterActorEvent]] path, so `LocalTimeManagerBase.
  * forceDestructActiveActors` — which only reaches actors it tracks in `scheduledActors`/
  * `runningEvents` — can never send it a `DestructEvent` at simulation end, and its Time-Warp-buffered
  * `report()` calls (§4) are never flushed. Silently dropped 282 of 287 report rows in a real
  * conservative-vs-optimistic comparison (the `Link`/`RailLink` enter/leave-link reports).
  *
  * Deliberately a **separate** message from `RegisterActorEvent`, not an added field on it: unlike
  * that event (which the receiving `LocalTimeManagerBase.registerActor` always turns into a real
  * `scheduleEvent` call, dispatching a `SpontaneousEvent`), this one must NOT cause any dispatch.
  * An actor with no real `actSpontaneous` override (e.g. `RailLink`, which has none at all) would
  * otherwise be caught by `handleSpontaneous`'s "did you call onFinishSpontaneous" safety net,
  * which auto-reschedules it for the next tick forever — turning an inert infrastructure actor into
  * a permanent busy-loop. This event only makes the actor *reachable* for a forced destruct at
  * simulation end; it never enters `scheduledActors`.
  *
  * @param actorId
  *   the registering actor's entity id
  * @param identify
  *   full identity (class type, actor ref/shard routing info) — same shape `RegisterActorEvent`
  *   carries, needed for `sendDestructEvent` to route the eventual `DestructEvent` correctly
  */
case class RegisterPassiveActorEvent(actorId: String, identify: Option[Identify])
