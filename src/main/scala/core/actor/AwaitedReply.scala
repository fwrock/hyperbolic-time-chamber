package org.interscity.htc
package core.actor

import core.types.Tick

/** One actor's record of "I am genuinely still waiting for a specific reply", keyed by whatever
  * identifies the outstanding request (see [[SimulationBaseActor.registerAwaitedReply]]).
  *
  * Both synchronization policies need unresolved replies to prevent premature termination.
  * The registry is restored with checkpoints and migration snapshots: a wait registered by an
  * undone event must disappear, and a wait consumed by an undone reply must come back.
  *
  * @param kind
  *   what this reply is (e.g. "LinkAccessData") — a request site checks both key and kind match
  *   before treating an arriving reply as the one it's still waiting for.
  * @param sentAtTick
  *   the tick the request was sent at, for diagnostics only.
  * @param sendSeq
  *   Under Time Warp, this actor's `messageSendSeq` at send time -- the identity token for this specific request
  *   instance, not merely diagnostic. `outstandingAwaitedReplies.put` overwrites the prior entry
  *   for `key` on a fresh `registerAwaitedReply` (e.g. a rollback-triggered resend), which is
  *   correct for "what am I currently waiting for" but means `kind` alone can no longer tell a
  *   reply to the CURRENT request apart from one belonging to the request instance that overwrite
  *   just superseded -- both carry the same `key`/`kind`. `sendSeq` is what the reply payload
  *   itself must echo back (see `LinkAccessData.requestSeq`/`LinkInfoData.requestSeq`) so
  *   `SimulationBaseActor.isAwaitingReply` can reject a superseded request's reply as genuinely
  *   stale instead of wrongly accepting it -- see docs/TIME_WARP_DESIGN.md §10's rollback-cascade
  *   livelock this closes. Conservative sends retain seq=0 and use their existing model-state guards.
  */
final case class AwaitedReply(kind: String, sentAtTick: Tick, sendSeq: Long)
