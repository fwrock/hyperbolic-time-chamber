package org.interscity.htc
package core.actor.rollback

import core.types.Tick

/** Identity of one outbound message, stable across a Time Warp rollback/replay so the receiver can
  * always find the exact original send an anti-message refers to.
  *
  * `seq` is a per-sender monotonically increasing send counter that must *not* be restored by a
  * rollback — it lives outside whatever a checkpoint captures, so a resend produced by replay
  * always gets a fresh id rather than reusing one that might still be referenced by an in-flight
  * anti-message. This is what makes Time Warp's "aggressive cancellation" (always anti-message
  * everything after a rollback point, never try to detect that a replay reproduced an identical
  * message) safe and simple. See `docs/TIME_WARP_DESIGN.md` §10.
  *
  * Not yet wired into `ActorInteractionEvent` — that's §10's scope (anti-messages), built after
  * [[RollbackHistoryHandler]] (§6/§7). Introduced now only because [[LoggedEvent]] needs a typed
  * place to hold "what this event sent" from the start (`docs/TIME_WARP_DESIGN.md`'s own stated
  * reason for that ordering: anti-messages depend on the event log's send data "already being
  * correct").
  */
final case class MessageId(senderId: String, tick: Tick, seq: Long)
