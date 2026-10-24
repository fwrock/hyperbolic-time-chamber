package org.interscity.htc
package core.actor.rollback

import core.actor.manager.loadbalance.migration.MigrationSnapshot
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
  * `event`/`replayEventFn` are typed `AnyRef`, not `core.entity.event.BaseEvent[?]` as originally
  * assumed when [[LoggedEvent]] was first introduced: `SpontaneousEvent` extends `BaseEvent`, but
  * `ActorInteractionEvent` (the other real event type an actor processes, via `actInteractWith`)
  * does not — it's a separate, unrelated case class. Found when actually wiring this handler into
  * `SimulationBaseActor`; `AnyRef` is the true common supertype of everything this handler needs
  * to log.
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
  *   state, to walk it forward from a checkpoint to an exact target point in the log. Takes the
  *   full [[LoggedEvent]], not just its `event` field, so the caller can also restore
  *   `recordedReads` (or anything else logged alongside the event) for the duration of the
  *   replay — see `docs/TIME_WARP_DESIGN.md`'s model-level audit, third finding.
  */
final class RollbackHistoryHandler(
  checkpointInterval: Int,
  captureSnapshotFn: () => MigrationSnapshot,
  restoreSnapshotFn: MigrationSnapshot => Unit,
  replayEventFn: LoggedEvent => Unit
) {
  require(checkpointInterval >= 1, s"checkpointInterval must be >= 1, got $checkpointInterval")

  private final case class Checkpoint(seq: Long, tick: Tick, snapshot: MigrationSnapshot)

  private val log: mutable.ArrayBuffer[LoggedEvent] = mutable.ArrayBuffer.empty
  private val checkpoints: mutable.ArrayBuffer[Checkpoint] = mutable.ArrayBuffer.empty
  private var eventsSinceCheckpoint: Int = 0
  private var committedBefore: Tick = Long.MinValue
  private var speculativeCount = 0
  private var floorScheduleOutcome: Option[Option[Tick]] = None

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
    committedBefore = Long.MinValue
    speculativeCount = 0
    floorScheduleOutcome = None
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
    event: AnyRef,
    tick: Tick,
    seq: Long,
    sentMessages: Seq[SentMessage] = Seq.empty,
    recordedReads: Vector[RecordedRead] = Vector.empty,
    scheduleOutcome: Option[Option[Tick]] = None
  ): Unit = {
    log += LoggedEvent(
      tick = tick,
      seq = seq,
      event = event,
      sentMessages = sentMessages,
      recordedReads = recordedReads,
      scheduleOutcome = scheduleOutcome
    )
    eventsSinceCheckpoint += 1
    if (tick >= committedBefore) speculativeCount += 1
    if (eventsSinceCheckpoint >= checkpointInterval) {
      checkpoints += Checkpoint(seq = seq, tick = tick, snapshot = captureSnapshotFn())
      eventsSinceCheckpoint = 0
    }
  }

  /** Restores the prefix before the earliest processed event at or after `targetTick`.
    * The entire suffix must be undone, even if a delayed spontaneous event moved time backward;
    * retaining such an event without replaying it would make the log disagree with restored state.
    * The caller cancels the returned events' outputs and reprocesses surviving inputs.
    *
    * A no-op (empty result, no restore/replay) if nothing is actually at or after `targetTick` —
    * the common case where a straggler's tick is still in the actor's future.
    *
    * @throws IllegalStateException
    *   if no checkpoint precedes the affected event
    */
  def rollbackTo(targetTick: Tick): Seq[LoggedEvent] = {
    log.iterator.filter(_.tick >= targetTick).map(_.seq).minOption match {
      case Some(firstUndoneSeq) => rollbackFrom(firstUndoneSeq)
      case None => Seq.empty
    }
  }

  /** A new interaction follows already-processed events at its tick, including its own cause. */
  def rollbackAfter(targetTick: Tick): Seq[LoggedEvent] = {
    log.iterator.filter(_.tick > targetTick).map(_.seq).minOption match {
      case Some(firstUndoneSeq) => rollbackFrom(firstUndoneSeq)
      case None => Seq.empty
    }
  }

  def rollbackBefore(order: core.runtime.EventOrder): Seq[LoggedEvent] = {
    val first = log.iterator.filter { logged =>
      val recorded = logged.event match {
        case e: core.entity.event.ActorInteractionEvent => e.order
        case e: core.entity.event.SpontaneousEvent => e.order
        case _ => None
      }
      recorded.exists(_ > order) || logged.tick > order.tick
    }.map(_.seq).minOption
    first.fold(Seq.empty[LoggedEvent])(rollbackFrom)
  }

  /** Cancellation targets one processed event; earlier events at the same tick must survive.
    * Undo its complete processing suffix so retained history always matches restored state.
    */
  def rollbackFrom(firstUndoneSeq: Long): Seq[LoggedEvent] = {
    val sorted = log.sortBy(_.seq).toSeq
    val undone = sorted.filter(_.seq >= firstUndoneSeq)
    if (undone.isEmpty) {
      return Seq.empty
    }

    val floor = checkpoints
      .filter(_.seq < firstUndoneSeq)
      .sortBy(_.seq)
      .lastOption
      .getOrElse(
        throw new IllegalStateException(
          s"rollbackFrom($firstUndoneSeq): no checkpoint before the target — " +
            s"was initialize() called before the first recordProcessedEvent?"
        )
      )

    restoreSnapshotFn(floor.snapshot)
    val toReplay = sorted.filter(le => le.seq > floor.seq && le.seq < firstUndoneSeq)
    toReplay.foreach(replayEventFn)

    log --= undone
    speculativeCount -= undone.count(_.tick >= committedBefore)
    checkpoints.filterInPlace(_.seq < firstUndoneSeq)
    eventsSinceCheckpoint = toReplay.size

    undone
  }

  /** Retain a floor strictly before GVT: a checkpoint at GVT already includes events that a
    * rollback to GVT must undo. Call only with a proven watermark including in-transit work.
    */
  def pruneBelow(gvt: Tick): Unit = {
    require(gvt >= committedBefore, "fossil frontier regression")
    val firstLive = log.iterator.filter(_.tick >= gvt).map(_.seq).minOption
    if (log.nonEmpty && firstLive.isEmpty) {
      val last = log.maxBy(_.seq)
      floorScheduleOutcome = lastScheduleOutcome
      // Quiescent commitment can checkpoint the complete committed prefix, including passive LPs.
      val floor = Checkpoint(last.seq, last.tick, captureSnapshotFn())
      checkpoints.clear()
      checkpoints += floor
      log.clear()
      eventsSinceCheckpoint = 0
    } else {
      checkpoints.filter(c => c.tick < gvt && firstLive.forall(c.seq < _)).maxByOption(_.seq).foreach { floor =>
        val dropped = log.iterator.filter(_.seq < floor.seq).toVector
        floorScheduleOutcome = dropped.reverseIterator.flatMap(_.scheduleOutcome).nextOption().orElse(floorScheduleOutcome)
        checkpoints.filterInPlace(_.seq >= floor.seq)
        log.filterInPlace(_.seq >= floor.seq)
      }
    }
    markCommitted(gvt)
  }

  def markCommitted(gvt: Tick): Unit = {
    require(gvt >= committedBefore, "commit frontier regression")
    committedBefore = gvt
    speculativeCount = log.count(_.tick >= gvt)
  }

  def uncommittedEventCount: Int = speculativeCount
  def retainedCounts: (Int, Int, Int) = (checkpoints.size, log.size, log.iterator.map(_.recordedReads.size).sum)

  /** Finds the logged event (if any) matching `predicate` — used to locate the original event an
    * incoming anti-message refers to, by matching its `MessageId` against each logged event's own
    * identity. Generic (`AnyRef => Boolean`) because this class doesn't know about
    * `ActorInteractionEvent`/`MessageId` specifically — the caller (the actor, which does know the
    * concrete event type) supplies the match logic.
    */
  def findEvent(predicate: AnyRef => Boolean): Option[LoggedEvent] = log.find(le => predicate(le.event))

  /** The schedule request of the latest retained event that made one (see
    * [[LoggedEvent.scheduleOutcome]]); `None` if no retained event ever did.
    */
  def lastScheduleOutcome: Option[Option[Tick]] =
    log.sortBy(_.seq).reverseIterator.flatMap(_.scheduleOutcome).nextOption().orElse(floorScheduleOutcome)

  /** Test/diagnostic hook — how many full-copy checkpoints are currently retained. */
  private[rollback] def checkpointCount: Int = checkpoints.size

  /** Test/diagnostic hook — how many log entries are currently retained. */
  private[rollback] def logSize: Int = log.size
}
