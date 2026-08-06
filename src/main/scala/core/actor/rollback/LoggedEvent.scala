package org.interscity.htc
package core.actor.rollback

import core.entity.event.BaseEvent
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
  *   the original event, kept so `replayEventFn` can re-execute it against a restored checkpoint
  * @param sentMessageIds
  *   identities of every message this actor sent while processing this event, for §10's
  *   anti-message cascade to turn into anti-messages once this entry is undone. Empty until §10
  *   wires up the capture; see [[MessageId]]'s doc for why this field exists ahead of that.
  */
final case class LoggedEvent(
  tick: Tick,
  seq: Long,
  event: BaseEvent[?],
  sentMessageIds: Seq[MessageId] = Seq.empty
)
