package org.interscity.htc
package core.actor

import core.types.Tick

/** One actor's record of "I am genuinely still waiting for a specific reply", keyed by whatever
  * identifies the outstanding request (see [[SimulationBaseActor.registerAwaitedReply]]).
  *
  * Deliberately NOT in `core.actor.rollback` alongside [[core.actor.rollback.SentMessage]]/
  * [[core.actor.rollback.MessageId]] — those exist to be captured, replayed, and retracted by the
  * rollback machinery; this type exists specifically to be invisible to it. A Time Warp checkpoint
  * restore (`RollbackHistoryHandler.rollbackTo`) can revert `state.status` (a wait-marker) to its
  * pre-request value while a genuine, un-retractable reply is still in flight; a stale-guard that
  * only consults `state.status` then drops that reply as "stale" and the sender is never
  * rescheduled. Tracking the wait outside `state`, untouched by rollback/replay/migration, gives
  * "stale" a meaningful definition again: stale means "a newer request for the same thing has
  * since been sent," not "a rollback happened to revert my status."
  *
  * @param kind
  *   what this reply is (e.g. "LinkAccessData") — a request site checks both key and kind match
  *   before treating an arriving reply as the one it's still waiting for.
  * @param sentAtTick
  *   the tick the request was sent at, for diagnostics only.
  * @param sendSeq
  *   this actor's `messageSendSeq` at send time -- THE identity token for this specific request
  *   instance, not merely diagnostic. `outstandingAwaitedReplies.put` overwrites the prior entry
  *   for `key` on a fresh `registerAwaitedReply` (e.g. a rollback-triggered resend), which is
  *   correct for "what am I currently waiting for" but means `kind` alone can no longer tell a
  *   reply to the CURRENT request apart from one belonging to the request instance that overwrite
  *   just superseded -- both carry the same `key`/`kind`. `sendSeq` is what the reply payload
  *   itself must echo back (see `LinkAccessData.requestSeq`/`LinkInfoData.requestSeq`) so
  *   `SimulationBaseActor.isAwaitingReply` can reject a superseded request's reply as genuinely
  *   stale instead of wrongly accepting it -- see docs/TIME_WARP_DESIGN.md §10's rollback-cascade
  *   livelock this closes.
  */
final case class AwaitedReply(kind: String, sentAtTick: Tick, sendSeq: Long)
