package org.interscity.htc
package core.actor.rollback

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.event.BaseEvent
import core.types.Tick

import scala.collection.mutable

/** Per-actor checkpoint + event-log handler for Time Warp's optimistic rollback
  * (`docs/TIME_WARP_DESIGN.md` §6/§7). Composed into a participating actor the same lambda-only,
  * no-`ActorRef` way `CarMicroHandler`/`CarLinkHandler` are — this class holds no reference to the
  * owning actor beyond the four callbacks passed at construction, and no Pekko dependency.
  *
  * Strategy: periodic full-copy checkpointing + replay, not per-event copy-state-saving or
  * incremental delta-saving (see §7 for why: reuses `BaseActor.buildMigrationSnapshot`/
  * `applyMigrationSnapshot` as-is, and needing to instrument every state mutation for delta-saving
  * would be a much larger blast radius for the same asymptotic memory win). A full snapshot is
  * taken every `checkpointInterval` processed events (plus always on the very first one, via
  * [[initialize]], so a rollback can always reach back to "before anything happened"); everything
  * in between is reconstructed by restoring the nearest earlier checkpoint and replaying the
  * intervening log entries.
  *
  * Deliberately not generic over a state type `T` the way `docs/TIME_WARP_DESIGN.md`'s original
  * sketch showed (`RollbackHistoryHandler[T <: BaseState]`): `MigrationSnapshot` itself already
  * serializes state to an opaque JSON blob (`stateJson`/`stateClassName`), so `T` would never
  * actually appear in this class's body — carrying it anyway would be an unused type parameter,
  * not real type safety.
  *
  * @param checkpointInterval
  *   how many processed events between full-copy checkpoints; must be >= 1. No default tuned yet
  *   — `docs/TIME_WARP_DESIGN.md`'s "Open questions" explicitly defers picking one until measured
  *   against a real scenario.
  * @param captureSnapshotFn
  *   `() => actor.buildMigrationSnapshot()` — must be called synchronously by the actor on itself
  *   at a quiescent point (right after finishing an event), matching the same race-free pattern
  *   `buildMigrationSnapshot` already uses for shard migration; see §7's "Gap C" cross-reference.
  * @param restoreSnapshotFn
  *   `actor.applyMigrationSnapshot(_)` — replaces the actor's live state with a checkpoint's.
  * @param replayEventFn
  *   re-executes one already-processed event's logic against the actor's current (just-restored)
  *   state, to walk it forward from a checkpoint to an exact target point in the log.
  */
final class RollbackHistoryHandler(
  checkpointInterval: Int,
  captureSnapshotFn: () => MigrationSnapshot,
  restoreSnapshotFn: MigrationSnapshot => Unit,
  replayEventFn: BaseEvent[?] => Unit
) {
  require(checkpointInterval >= 1, s"checkpointInterval must be >= 1, got $checkpointInterval")

  private final case class Checkpoint(seq: Long, tick: Tick, snapshot: MigrationSnapshot)

  private val log: mutable.ArrayBuffer[LoggedEvent] = mutable.ArrayBuffer.empty
  private val checkpoints: mutable.ArrayBuffer[Checkpoint] = mutable.ArrayBuffer.empty
  private var eventsSinceCheckpoint: Int = 0

  /** Establishes the floor checkpoint "before anything happened," at `startTick`. Must be called
    * once, before the first [[recordProcessedEvent]] — without it, a rollback targeting the
    * actor's very first-ever processed event would have no earlier state to restore to. Also
    * resets any prior log/checkpoint history, so it doubles as a reset if the actor is ever
    * reconstructed from scratch (e.g. after a real migration restore, once that path exists for
    * Time Warp actors).
    */
  def initialize(startTick: Tick): Unit = {
    log.clear()
    checkpoints.clear()
    eventsSinceCheckpoint = 0
    checkpoints += Checkpoint(seq = 0L, tick = startTick, snapshot = captureSnapshotFn())
  }

  /** Records one event this actor just finished processing. Cheap (an append) on every call; takes
    * a full checkpoint only every `checkpointInterval` calls.
    *
    * @param seq
    *   this actor's own monotonically increasing processed-event counter (see [[LoggedEvent.seq]])
    *   — must be strictly greater than every previously recorded `seq`, and greater than the `seq`
    *   passed to [[initialize]] (`0L`).
    */
  def recordProcessedEvent(
    event: BaseEvent[?],
    tick: Tick,
    seq: Long,
    sentMessageIds: Seq[MessageId] = Seq.empty
  ): Unit = {
    log += LoggedEvent(tick = tick, seq = seq, event = event, sentMessageIds = sentMessageIds)
    eventsSinceCheckpoint += 1
    if (eventsSinceCheckpoint >= checkpointInterval) {
      checkpoints += Checkpoint(seq = seq, tick = tick, snapshot = captureSnapshotFn())
      eventsSinceCheckpoint = 0
    }
  }

  /** Rolls this actor back to before `targetTick`: every logged event at `tick >= targetTick` is
    * undone (state-wise) and returned to the caller, who is responsible for turning each into an
    * anti-message once §10 wires that up. Every event at `tick < targetTick` is preserved by
    * restoring the nearest earlier checkpoint and replaying forward to exactly that point.
    *
    * A no-op (empty result, no restore/replay) if nothing is actually at or after `targetTick` —
    * the common case where a straggler's tick is still in the actor's future.
    *
    * @throws IllegalStateException
    *   if called before [[initialize]] established a floor, or if `targetTick` is at or before the
    *   very first checkpoint (both indicate a caller bug, not a runtime condition to recover from)
    */
  def rollbackTo(targetTick: Tick): Seq[LoggedEvent] = {
    val sorted = log.sortBy(_.seq).toSeq
    val undone = sorted.filter(_.tick >= targetTick)
    if (undone.isEmpty) {
      return Seq.empty
    }

    val firstUndoneSeq = undone.map(_.seq).min
    val floor = checkpoints
      .filter(_.seq < firstUndoneSeq)
      .sortBy(_.seq)
      .lastOption
      .getOrElse(
        throw new IllegalStateException(
          s"rollbackTo($targetTick): no checkpoint before seq=$firstUndoneSeq — " +
            s"was initialize() called before the first recordProcessedEvent?"
        )
      )

    restoreSnapshotFn(floor.snapshot)
    val toReplay = sorted.filter(le => le.seq > floor.seq && le.seq < firstUndoneSeq)
    toReplay.foreach(le => replayEventFn(le.event))

    log --= undone
    checkpoints.filterInPlace(_.seq < firstUndoneSeq)
    eventsSinceCheckpoint = toReplay.size

    undone
  }

  /** Fossil collection: once nothing before `gvt` can ever be needed again (per the GVT
    * watermark's own guarantee, `docs/TIME_WARP_DESIGN.md` §3), discard every checkpoint/log entry
    * strictly older than the latest checkpoint at or before `gvt` — that checkpoint itself is kept
    * as the new floor, since a future rollback could still target exactly `gvt`.
    */
  def pruneBelow(gvt: Tick): Unit =
    checkpoints.filter(_.tick <= gvt).sortBy(_.seq).lastOption.foreach {
      floor =>
        checkpoints.filterInPlace(_.seq >= floor.seq)
        log.filterInPlace(_.seq >= floor.seq)
    }

  /** Test/diagnostic hook — how many full-copy checkpoints are currently retained. */
  private[rollback] def checkpointCount: Int = checkpoints.size

  /** Test/diagnostic hook — how many log entries are currently retained. */
  private[rollback] def logSize: Int = log.size
}
