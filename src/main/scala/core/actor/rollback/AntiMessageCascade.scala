package org.interscity.htc
package core.actor.rollback

/** Pure cascade math for Time Warp's anti-message protocol (`docs/TIME_WARP_DESIGN.md` §10):
  * turns the events a rollback just undid into the anti-messages that must be sent to retract
  * their effects downstream.
  *
  * There is deliberately no recursive "cascade" data structure here — per §10's own design, the
  * recursion happens across actors, not within one computation: each [[SentMessage]] returned
  * becomes one real anti-message sent to its receiver; that receiver reacts by calling
  * `RollbackHistoryHandler.rollbackTo` on *itself* (the same method used for straggler-driven
  * rollback — no separate mechanism), which returns *its own* undone events, which get run back
  * through this same function to produce the next wave. Termination is guaranteed because no
  * rollback can ever be asked to go before GVT, bounding every cascade.
  *
  * "Aggressive cancellation" per §10: always anti-message everything a rollback undid, never try
  * to detect that a subsequent replay reproduced an identical message and skip re-sending its
  * anti-message — that's why this is a flat `flatMap`, not a diff against what replay re-sends.
  */
object AntiMessageCascade {

  /** Every message that must be anti-messaged as a result of undoing `undone` — one per
    * [[SentMessage]] recorded on each undone [[LoggedEvent]], across every undone event, in no
    * particular order (callers needing a specific send order should sort by `messageId.seq`).
    */
  def messagesToRetract(undone: Seq[LoggedEvent]): Seq[SentMessage] =
    undone.flatMap(_.sentMessages)
}
