package org.interscity.htc
package core.actor

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.cluster.sharding.ShardRegion
import core.actor.manager.loadbalance.migration.{ MigrationSnapshot, MigrationStateStoreRegistry }
import core.actor.rollback.RollbackHistoryHandler
import core.api.SimulatorSettingsRegistry
import core.entity.event.{ ActorInteractionEvent, FinishEvent, SpontaneousEvent }
import core.entity.event.control.migration.{ MigrationContextEvent, MigrationRestoredAckEvent, NoPendingMigrationEvent, QueryMigrationEvent }
import core.types.Tick
import core.entity.state.BaseState
import core.entity.control.LamportClock
import core.util.{ IdUtil, JsonUtil, StringPool, StringUtil }

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, RegisterActorEvent }
import org.interscity.htc.model.hybrid.util.DynamicWeightCache
import org.htc.protobuf.core.entity.event.control.load.{ InitializeEntityAckEvent, StartEntityAckEvent }
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.{ InitializeEvent, NeedsPostLoadRegistrationEvent, PostLoadRegistrationAckEvent, PostLoadRegistrationEvent }
import org.interscity.htc.core.entity.event.control.report.ReportEvent
import org.interscity.htc.core.enumeration.{ ReportTypeEnum, TimeManagerTypeEnum }
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.{ LoadBalancedDistributed, PoolDistributed }
import org.interscity.htc.core.metrics.core.ActorMetrics

import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized

import core.entity.event.EntityEnvelopeEvent
import core.util.ActorCreatorUtil.createShardRegion
import core.entity.event.data.InitializeData

/** Base actor for simulation entities that require time management, spontaneous events, Lamport
  * clocks, and reporting capabilities. Extends the generic BaseActor with simulation-specific
  * functionality.
  *
  * @param properties
  *   The properties containing simulation-specific configuration
  * @tparam T
  *   The state type of the actor
  */
abstract class SimulationBaseActor[T <: BaseState](
  private var properties: Properties
)(implicit m: Manifest[T])
    extends BaseActor[T](properties) {

  protected var startTick: Tick = MinValue
  private val lamportClock = new LamportClock()
  protected var currentTick: Tick = 0

  protected val relationships: mutable.Map[String, ShardActorId] =
    if (properties != null && properties.relationships != null && properties.relationships.nonEmpty)
      mutable.Map[String, ShardActorId]() ++= properties.relationships
    else
      mutable.Map[String, ShardActorId]()

  /** Backward-compat alias for [[relationships]].
    * @deprecated
    *   Use relationships instead.
    */
  @deprecated("Use relationships instead", "2.8.0")
  protected def dependencies: mutable.Map[String, ShardActorId] = relationships

  protected var reporters: mutable.Map[ReportTypeEnum, ActorRef] =
    if (properties != null) properties.reporters else null

  protected var timeManagers: mutable.Map[String, ActorRef] =
    if (properties != null && properties.timeManagers != null) properties.timeManagers
    else mutable.Map[String, ActorRef]()

  /** Derived from `timeManagers`' own key rather than `properties.defaultTimeManagerType`, so
    * `PoolDistributed` actors (constructed with `properties.data` already set, via
    * `ActorCreatorUtil.createPoolActor` — never routed through `onInitialize`) still pick up the
    * scenario's chosen strategy at construction time. See `docs/TIME_WARP_DESIGN.md`.
    */
  protected var currentTimeManagerType: String =
    if (properties != null && properties.timeManagers != null && properties.timeManagers.nonEmpty)
      properties.timeManagers.keys.head
    else if (properties != null) properties.defaultTimeManagerType
    else TimeManagerTypeEnum.DISCRETE_EVENT

  protected var creatorManager: ActorRef =
    if (properties != null) properties.creatorManager else null
  private var currentTimeManager: ActorRef = uninitialized
  private var _spontaneousFinishSent     = false
  private var currentGeneration: Long = 0L

  private val pendingDynamicInits: mutable.Map[String, (ActorRef, InitializeEvent)] = mutable.Map.empty
  private val dynamicActorClassTypes: mutable.Map[String, String] = mutable.Map.empty

  // --- Time Warp (docs/TIME_WARP_DESIGN.md) --- everything below is gated behind
  // `currentTimeManagerType == TimeManagerTypeEnum.TIME_WARP`, which nothing sets yet (see the
  // design doc's step-4/5 implementation log) — under every currently reachable configuration
  // (i.e. conservative mode, always, today) `rollbackHandler` is never touched, so its `lazy`
  // initializer never runs and this costs conservative-mode actors nothing.
  //
  // This actor's own monotonically increasing processed-event counter — the `seq` RollbackHistoryHandler
  // orders its log by (see LoggedEvent.seq's doc). A plain actor-local `var`, not `state`: safe per
  // CLAUDE.md's rule for actor-local mutable fields, since it holds no reply obligation and is
  // cheap to recompute/reset — it only orders this actor's own already-in-memory replay log, not
  // anything another actor is waiting to hear back about.
  private var processedEventSeq: Long = 0L

  // This actor's own monotonically increasing SEND counter (docs/TIME_WARP_DESIGN.md §10's
  // MessageId.seq — a distinct counter from processedEventSeq above, which orders processed
  // events, not sends). Deliberately never included in buildMigrationSnapshot/applyMigrationSnapshot
  // and never touched by rollbackTo/replayLoggedEvent: §10 requires it survive a rollback
  // untouched, so a resend produced by replay always gets a fresh id rather than reusing one a
  // still-in-flight anti-message might reference.
  private var messageSendSeq: Long = 0L

  // Every message sent by sendMessageTo while the CURRENTLY-processing event runs, so it can be
  // attached to that event's RollbackHistoryHandler log entry (LoggedEvent.sentMessages) once
  // processing finishes — the data AntiMessageCascade needs to retract them on a future rollback.
  // Cleared at the start of handleSpontaneous/handleInteractWith; only ever populated when
  // isTimeWarp (see sendMessageTo), so this stays empty and unused for conservative-mode actors.
  private val currentEventSentMessages: mutable.ArrayBuffer[core.actor.rollback.SentMessage] = mutable.ArrayBuffer.empty

  // Every DynamicWeightCache.getWeight(linkId, ...) result observed while the CURRENTLY-processing
  // event runs (docs/TIME_WARP_DESIGN.md's model-level audit, third finding) -- flushed into that
  // event's RollbackHistoryHandler log entry (LoggedEvent.dynamicWeightReads) once processing
  // finishes, so a future replay of this exact event can force the same reads via
  // DynamicWeightCache.withOverride instead of hitting the live, real-time-shifted cache. Cleared at
  // the start of handleSpontaneous/handleInteractWith; only ever populated when isTimeWarp (the
  // whole event body runs inside DynamicWeightCache.withRecording(this) in that case), so this stays
  // empty and unused for conservative-mode actors.
  private val currentEventDynamicWeightReads: mutable.Map[String, Double] = mutable.Map.empty

  // What the CURRENTLY-processing event asked the time manager to schedule for this actor: None if
  // it never asked, Some(None) if it finished with no next tick (which clears every pending tick on
  // the TM side), Some(Some(t)) if it asked for tick t. Flushed into LoggedEvent.scheduleOutcome so
  // a rollback can restore the schedule the last surviving event had left, see
  // resyncScheduleAfterRollback. Only populated when isTimeWarp.
  private var currentEventScheduleOutcome: Option[Option[Tick]] = None

  // Replay does not resend, so it allocates no send seqs; the awaited-reply entry a replayed
  // request registers must carry the seq its ORIGINAL send had (the reply echoes it back). Filled
  // from the replayed event's LoggedEvent.sentMessages and consumed one per skipped sendMessageTo.
  private var replaySendSeqs: Iterator[Long] = Iterator.empty
  private var replayLastSendSeq: Long = -1L

  // Time Warp only (isTimeWarp-gated, zero cost under conservative mode). Tracks "am I genuinely
  // still waiting for a specific reply" OUTSIDE of `state`, specifically so a checkpoint restore
  // (RollbackHistoryHandler.rollbackTo) cannot silently wipe it the way state.status can be wiped.
  // Part of the migration snapshot, so a checkpoint restore plus replay rebuilds it exactly as it was
  // at the restored point: waits registered by undone events disappear, and a wait an undone event
  // consumed comes back. A fresh registerAwaitedReply for the same key supersedes (overwrites) any
  // prior entry, so "stale" means "a newer request for the same thing has since been sent."
  private val outstandingAwaitedReplies: mutable.Map[String, core.actor.AwaitedReply] = mutable.Map.empty

  /** No-op unless [[isTimeWarp]]. Overwrites any existing entry for `key` -- see
    * [[outstandingAwaitedReplies]]'s doc for why a fresh registration superseding a stale one is
    * the intended behavior, not a bug.
    */
  protected def registerAwaitedReply(key: String, kind: String): Unit =
    if (isTimeWarp) {
      val sendSeq = if (isReplaying && replayLastSendSeq >= 0L) replayLastSendSeq else messageSendSeq
      outstandingAwaitedReplies.put(key, core.actor.AwaitedReply(kind = kind, sentAtTick = currentTick, sendSeq = sendSeq))
    }

  /** Under [[isTimeWarp]], true iff an entry is registered for `key` with a matching `kind` AND
    * `requestSeq` matches that entry's `sendSeq` -- i.e. this reply belongs to the CURRENTLY
    * registered request instance for `key`, not one a rollback-then-resend already superseded.
    *
    * `requestSeq` is the identity token the reply payload itself must carry back (e.g.
    * `LinkAccessData.requestSeq`/`LinkInfoData.requestSeq`, echoed by the receiver from the
    * original request's `ActorInteractionEvent.seq`) -- see `AwaitedReply.sendSeq`'s doc for why
    * `kind` alone isn't enough: a fresh `registerAwaitedReply` for the same `key` overwrites the
    * prior entry (by design, see `outstandingAwaitedReplies`'s doc), so a `kind`-only check
    * wrongly accepts a stale reply that still belongs to the request instance that overwrite just
    * superseded. Under conservative mode this primitive is never consulted by call sites (they
    * keep their existing `state.status`-only guard), so `false` here is never actually observed --
    * returned only so this method is total rather than throwing.
    */
  protected def isAwaitingReply(key: String, kind: String, requestSeq: Long): Boolean =
    isTimeWarp && outstandingAwaitedReplies.get(key).exists(r => r.kind == kind && r.sendSeq == requestSeq)

  /** Removes the entry for `key` if present; no-op otherwise. */
  protected def consumeAwaitedReply(key: String): Unit =
    outstandingAwaitedReplies.remove(key)

  /** Read-only accessor onto `outstandingAwaitedReplies`, for subclasses/tests that need to know
    * the identity token (`AwaitedReply.sendSeq`) of whatever is currently registered for `key`,
    * without widening the map itself to `protected`. `None` if nothing is currently registered.
    */
  protected def awaitedReplySendSeq(key: String): Option[Long] =
    outstandingAwaitedReplies.get(key).map(_.sendSeq)

  // report() calls made while isTimeWarp, held back until the GVT watermark passes their tick
  // (docs/TIME_WARP_DESIGN.md §4 — decided when the design was written, never actually implemented
  // until now). Without this, report() emits to the reporter pipeline (ClickHouse/Kafka/Prometheus)
  // immediately regardless of synchronization strategy, so speculative execution a later rollback
  // undoes has already reported data for a branch that turns out never to have happened — silently,
  // since nothing about rollbackTo touches the reporter pipeline. Only ever populated when
  // isTimeWarp (see report(event)), so this stays empty and unused for conservative-mode actors.
  private val reportBuffer: mutable.ArrayBuffer[(ReportEvent, ActorRef)] = mutable.ArrayBuffer.empty

  /** True while [[replayLoggedEvent]] is re-executing an already-processed event's business logic
    * against a just-restored checkpoint, as opposed to a live dispatch. The one flag that makes
    * replay safe: [[onFinishSpontaneous]] and [[scheduleEvent]] both check it and skip signaling
    * the time manager while it's set (see each method's own doc for why), while `sendMessageTo` is
    * deliberately left unguarded — `docs/TIME_WARP_DESIGN.md` §10 wants replay to actually resend
    * messages, each getting a fresh `MessageId`, so the *previous* (now-invalidated) sends can be
    * anti-messaged instead. Never true outside of [[replayLoggedEvent]]'s own call stack, and
    * [[replayLoggedEvent]] is only ever invoked by [[rollbackHandler]]'s `replayEventFn`, itself
    * only reachable from `RollbackHistoryHandler.rollbackTo` — which nothing calls yet (see
    * `docs/TIME_WARP_DESIGN.md`'s implementation log for this step), so this is provably always
    * `false` today, same as [[isTimeWarp]].
    */
  private var isReplaying: Boolean = false

  /** Per-actor checkpoint + event-log handler (`docs/TIME_WARP_DESIGN.md` §6/§7), composed the same
    * lambda-only, no-`ActorRef` way `CarMicroHandler` is. `replayEventFn` delegates to
    * [[replayLoggedEvent]], which sets [[isReplaying]] around the re-dispatch so the protocol
    * side effects `actSpontaneous`/`actInteractWith` can trigger (`onFinishSpontaneous`,
    * `scheduleEvent`) don't repeat during replay the way they would during a live dispatch — see
    * [[isReplaying]]'s doc for the full reasoning.
    */
  protected lazy val rollbackHandler: RollbackHistoryHandler = new RollbackHistoryHandler(
    checkpointInterval =
      SimulatorSettingsRegistry.getInt("htc.time-warp.checkpoint-interval", context.system.settings.config),
    captureSnapshotFn = () => buildMigrationSnapshot(),
    restoreSnapshotFn = snapshot => applyMigrationSnapshot(snapshot),
    replayEventFn = logged => replayLoggedEvent(logged)
  )

  /** Re-executes one already-logged event's business logic against the actor's current
    * (just-restored) state, to walk it forward from a checkpoint to an exact log position — see
    * [[isReplaying]] for why this is safe to do without repeating `onFinishSpontaneous`/
    * `scheduleEvent`'s time-manager signaling. Mirrors `handleSpontaneous`/`handleInteractWith`'s
    * own pre-dispatch bookkeeping (`currentTick`/`currentTimeManager`/`currentGeneration`
    * assignment, Lamport clock update) but deliberately skips their *post*-dispatch bookkeeping
    * (the safety-net "did you call onFinishSpontaneous" check, `recordProcessedEvent` itself) —
    * both are meaningless here: nothing is dispatching a *new* event for the time manager to track,
    * and this event is already in the log being replayed, not a fresh one to log again.
    *
    * Known scope limit: `sendMessageTo` calls made during this replay populate
    * [[currentEventSentMessages]] same as a live dispatch, but nothing here flushes that back onto
    * the original `LoggedEvent` being replayed (unlike a live dispatch, which flushes it via
    * `recordProcessedEvent`) — so if *this exact event* is ever rolled back a second time, its
    * anti-message cascade would use the sends from its *first* dispatch, not this replay's. A real
    * gap for a repeated-rollback-of-the-same-event edge case, not designed further here; the
    * common single-rollback path this step's tests exercise is unaffected. The same caveat applies
    * to [[currentEventDynamicWeightReads]] below — a second rollback of this same replayed event
    * would replay it a third time using the ORIGINAL live dispatch's recorded reads (from
    * `logged.dynamicWeightReads`, untouched here), not this replay's own — same deeper-nested-
    * rollback edge case, not this event type's own new gap.
    *
    * The whole business-logic call is wrapped in `DynamicWeightCache.withOverride(logged.
    * dynamicWeightReads)` (`docs/TIME_WARP_DESIGN.md`'s model-level audit, third finding) so any
    * `getWeight` read inside `actSpontaneous`/`actInteractWith` — however deeply nested, e.g. inside
    * a route calculation — reproduces exactly what the original live dispatch saw, instead of
    * whatever the live dynamic-weight cache holds by the time this replay runs.
    */
  private def replayLoggedEvent(logged: core.actor.rollback.LoggedEvent): Unit = {
    isReplaying = true
    replaySendSeqs = logged.sentMessages.iterator.map(_.messageId.seq)
    replayLastSendSeq = -1L
    try
      DynamicWeightCache.withOverride(logged.dynamicWeightReads) {
        logged.event match {
          case spontaneous: SpontaneousEvent =>
            currentTick = spontaneous.tick
            currentTimeManager = spontaneous.actorRef
            currentGeneration = spontaneous.generation
            actSpontaneous(spontaneous)
          case interaction: ActorInteractionEvent =>
            updateLamportClock(interaction.lamportTick)
            if (interaction.tick > currentTick) {
              currentTick = interaction.tick
            }
            actInteractWith(interaction)
          case other =>
            logWarn(s"$getEntityId: replayLoggedEvent doesn't know how to replay a ${other.getClass.getName}")
        }
      }
    catch
      case e: Throwable =>
        logError(s"Exception replaying a logged event for $getEntityId: ${e.getMessage}")
        e.printStackTrace()
    finally {
      isReplaying = false
      replaySendSeqs = Iterator.empty
      replayLastSendSeq = -1L
    }
  }

  /** True only under Time Warp — the one gate every new-but-not-yet-safe-to-activate call site
    * below checks before touching [[rollbackHandler]], so conservative mode is provably unaffected.
    */
  private def isTimeWarp: Boolean = currentTimeManagerType == TimeManagerTypeEnum.TIME_WARP

  /** Hook called from [[rollbackAndCascade]] (Time Warp only) right after undone state is
    * restored, so a subclass can drop any actor-local, non-`state` stash whose irrevocable
    * follow-up (e.g. a dynamic-actor spawn deferred past a GVT watermark, see
    * `docs/TIME_WARP_DESIGN.md`) was tied to a tick this rollback just undid. Default: no-op —
    * only relevant to subclasses that actually defer an irrevocable step this way.
    */
  protected def onRollbackDiscardPending(targetTick: Tick): Unit = ()

  /** Called from [[handleInteractWith]] before it would otherwise treat a tick-behind interaction
    * event as a genuine causality violation requiring a full rollback. Returning `true` tells the
    * generic layer "I can already prove, without rolling back, that this specific message carries
    * no information my current state doesn't already correctly reflect — drop it, no processing, no
    * rollback." Default `false`: every actor that doesn't override this behaves exactly as before
    * (unconditional rollback on any tick-behind interaction).
    *
    * The only sound way to answer `true` is an identity check equivalent to
    * [[isAwaitingReply]]'s: this message is a reply to a request this actor has since superseded
    * with a fresh one for the same logical exchange (see [[Movable]]'s override for the concrete
    * `EnterLinkData`/`LeaveLinkData` case this was built for). It is NOT sound to answer `true`
    * based on `state` alone (e.g. "I've already moved past this" by business-state inspection) —
    * `state` is exactly what a genuine causality violation would still need corrected via rollback.
    * Found 2026-09-11 (docs/KNOWN_GAPS.md's "Report loss/duplication" entry, Cause 3): a real run
    * showed `Link -> Car ReceiveEnterLinkInfo` dominating rollback volume — Car had already
    * re-sent a fresh `EnterLinkData` after an unrelated rollback, so the Link's now-superseded
    * reply to the OLD request straggled Car's advanced `currentTick`, paying a full rollback+
    * anti-message-cascade cost to reach the same "discard, do nothing" outcome
    * `CarLinkHandler`'s own staleness gate would have reached anyway, had the message ever gotten
    * that far.
    */
  protected def isKnownSupersededReply(event: ActorInteractionEvent): Boolean = false

  /** The straggler trigger (`docs/TIME_WARP_DESIGN.md` §10): rolls this actor back to before
    * `targetTick` and sends a real anti-message for every send that gets undone in the process.
    * Called from [[handleInteractWith]] for both cases that need it — a genuinely causally-earlier
    * interaction arriving after this actor already ran ahead of it, and an incoming anti-message
    * itself (whose target tick is looked up via [[findLoggedEventForMessage]]) — since both reduce
    * to the same "undo back to a tick, cascade what that undoes" operation
    * `RollbackHistoryHandler.rollbackTo` already implements.
    */
  private def rollbackAndCascade(targetTick: Tick): Seq[core.actor.rollback.LoggedEvent] = {
    val currentTickBeforeRestore = currentTick
    val undone = rollbackHandler.rollbackTo(targetTick)
    if (undone.isEmpty) {
      // #timewarp-trace
      core.actor.trace.TimeWarpTrace.trace(
        getEntityId,
        currentTickBeforeRestore,
        "rollback_undone_empty",
        s"targetTick=$targetTick nothing logged at/after targetTick -- either a stale/duplicate " +
          s"rollback request for this actor, or this actor's log was already pruned/rolled back past " +
          s"targetTick by an earlier overlapping rollback"
      )
      return Seq.empty
    }
    // Diagnostic visibility for the rollback-orphaned-reply mechanism suspected in a real
    // tls_test livelock (docs/TIME_WARP_DESIGN.md §10): a restore here can revert business state
    // (e.g. CarState.status) that a since-answered, unretractable request depended on, orphaning
    // that reply. logWarn (not debug) since rollbacks-that-restore-a-stale-marker should be rare
    // enough that this needs to show up in a normal repro run's log tail, not just on request.
    logWarn(
      s"[TW-ROLLBACK] $getEntityId rolling back to targetTick=$targetTick " +
        s"(currentTick was $currentTickBeforeRestore, undoing ${undone.size} logged event(s))"
    )
    // §4: any report() buffered for a tick this rollback just undid must be dropped, not flushed
    // later — it describes state that turned out never to have happened. Reports already flushed
    // (tick < targetTick, or a tick that already passed GVT before this rollback) are unaffected;
    // they were only ever flushed once GVT made them irrevocable in the first place.
    if (isTimeWarp) {
      val (kept, discarded) = reportBuffer.partition(_._1.tick < targetTick)
      if (discarded.nonEmpty) {
        reportBuffer.clear()
        reportBuffer ++= kept
        discarded.foreach {
          case (event, _) =>
            if (event.label != null) ActorMetrics.reportDiscardedByRollback.labels(event.label).inc()
        }
      }
    }
    onRollbackDiscardPending(targetTick)
    val retracted = core.actor.rollback.AntiMessageCascade.messagesToRetract(undone)
    // #timewarp-trace
    core.actor.trace.TimeWarpTrace.trace(
      getEntityId,
      currentTickBeforeRestore,
      "rollback_undone",
      s"targetTick=$targetTick undoneSeqs=${undone.map(_.seq).sorted.mkString(",")} " +
        s"undoneTicks=${undone.map(_.tick).sorted.mkString(",")} retractedMessageIds=${retracted.map(_.messageId).mkString(",")}"
    )
    retracted.foreach(sendAntiMessage)
    resyncScheduleAfterRollback()
    undone
  }

  /** The interactions a rollback undid that must still happen: their senders did not retract them,
    * so the rollback only moved them after the straggler, it did not cancel them. Undone spontaneous
    * events are not here, [[resyncScheduleAfterRollback]] makes the time manager dispatch those
    * again. `annihilated` is the message an incoming anti-message retracts, which never happened and
    * must not be reprocessed. Returned in tick order, so replaying them in sequence never produces a
    * straggler of its own.
    */
  private def interactionsToReprocess(
    undone: Seq[core.actor.rollback.LoggedEvent],
    annihilated: Option[core.actor.rollback.MessageId]
  ): Seq[ActorInteractionEvent] =
    undone
      .sortBy(le => (le.tick, le.seq))
      .map(_.event)
      .collect { case e: ActorInteractionEvent => e }
      .filterNot(e => e.isAntiMessage || annihilated.contains(e.messageId))

  /** After [[rollbackHandler]] undoes one or more processed events, this actor's own future
    * self-schedule can be wrong: the undone event(s), during their original live dispatch, already
    * sent a real `FinishEvent`/`ScheduleEvent` straight to `currentTimeManager`, registering a
    * future tick computed from state that turned out never to have happened. That registration
    * isn't touched by the anti-message cascade above — `onFinishSpontaneous`/`scheduleEvent` bypass
    * `sendMessageTo` (see their own docs), so they're never captured in `LoggedEvent.sentMessages`
    * and `AntiMessageCascade` never sees them to retract. Left alone, the stale tick sits in
    * `OptimisticLocalTimeManager.scheduledActors` forever (or until it's coincidentally reached),
    * corrupting that LTM's `nextTick`/LVT, which drags cluster-wide GVT and can stall the whole
    * simulation, not just this actor.
    *
    * Fix, in two ordered real (non-replay) sends to the same `currentTimeManager` — Pekko preserves
    * per-sender-per-receiver ordering, so the clear is guaranteed to land before the reschedule:
    *   1. `FinishEvent(scheduleTick = None)` — the one TM-side primitive that already exists for
    *      "unregister this actor from every future tick" (`LocalTimeManagerBase.finishEvent`'s
    *      `scheduleTick.isEmpty` sweep), so the stale entry is removed rather than left alongside a
    *      new one (which would be a double-dispatch bug, not a fix).
    *   2. [[scheduleEvent]] at the tick the last surviving event had requested (see
    *      [[RollbackHistoryHandler.lastScheduleOutcome]]), or at `currentTick` when no survivor ever
    *      scheduled — this actor's genuine post-rollback position, restored
    *      by `rollbackTo` itself (via `applyMigrationSnapshot`/`replayLoggedEvent`, both of which
    *      set `currentTick`). `LocalTimeManagerBase.scheduleEvent`'s own `highestProcessedTick`
    *      watermark bump takes care of never re-dispatching a tick already processed.
    *
    * `generation = Long.MaxValue` on the clearing `FinishEvent` deliberately bypasses
    * `finishEvent`'s stale-generation guard (`LocalTimeManagerBase.scala:184`): when the undone
    * event was this actor's own most recent live spontaneous dispatch, `currentGeneration` now
    * legitimately holds an *older* generation than the time manager's `dispatchGeneration` counter
    * — that counter is TM-side bookkeeping incremented on every dispatch and is never rolled back —
    * so the normal "reject an out-of-order FinishEvent" check would silently drop exactly the
    * corrective message this method exists to deliver. `generation` is read only for that one
    * comparison (never stored or propagated, see `FinishEvent.scala`), so this has no effect beyond
    * guaranteeing the clear always lands.
    *
    * Only ever called from [[rollbackAndCascade]], itself only reachable from a live
    * `actInteractWith` dispatch (never while [[isReplaying]]), so `currentTimeManager` is always
    * the real, live time manager to correct — same guard `onFinishSpontaneous` uses for the same
    * "never live-dispatched" edge case.
    */
  private def resyncScheduleAfterRollback(): Unit = {
    if (currentTimeManager == null) return
    currentTimeManager ! FinishEvent(
      end = currentTick,
      actorRef = self,
      identify = Identify(
        id = getEntityId.toLong,
        resourceId = IdUtil.format(properties.resourceId),
        classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
        actorRef = getPath,
        actorType = properties.actorType.toString
      ),
      scheduleTick = None,
      scheduleEvent = None,
      timeManager = currentTimeManager,
      destruct = false,
      generation = Long.MaxValue,
      awaitingReply = outstandingAwaitedReplies.nonEmpty
    )
    // Unregistering the stale TM entry above is always correct regardless of wait state -- it may
    // be clearing a schedule left by a different undone event than whatever this actor is still
    // waiting on. Only the forced re-dispatch below is conditional: if a genuine, unretracted wait
    // survives the registry restore, this actor is correctly disengaged (CLAUDE.md's
    // Synchronization Discipline) and must NOT be force-woken -- the real reply, when it arrives,
    // resolves it via the normal actInteractWith -> reply-handler -> onFinishSpontaneous path,
    // identical to conservative mode (that path never depended on this resync to unstick it).
    if (outstandingAwaitedReplies.nonEmpty) {
      logDebug(
        s"$getEntityId: rollback resync at tick=$currentTick skipped -- actor genuinely still " +
          s"awaiting a reply (${outstandingAwaitedReplies.keys.mkString(",")}), not force-dispatching"
      )
      // #timewarp-trace
      core.actor.trace.TimeWarpTrace.trace(
        getEntityId,
        currentTick,
        "rollback_resync_skipped_awaiting_reply",
        s"outstandingAwaitedReplies=${outstandingAwaitedReplies.mkString(",")}"
      )
      return
    }
    // The schedule the last surviving event left behind, not currentTick: that tick was already
    // processed, and a resync overwrites the TM's highestProcessedTick, so rescheduling it would
    // dispatch the same tick twice. With no surviving event that ever scheduled, the restored
    // currentTick is the only position there is.
    rollbackHandler.lastScheduleOutcome match {
      case Some(Some(nextTick)) => scheduleEvent(nextTick, resyncAfterRollback = true)
      case Some(None)           => ()
      case None                 => scheduleEvent(currentTick, resyncAfterRollback = true)
    }
  }

  /** Finds the logged event (if any) this actor recorded for processing the interaction identified
    * by `messageId` — i.e. the original send an incoming anti-message refers to. Matches on the
    * sender/tick/seq triple `ActorInteractionEvent.messageId` derives, since that's the only event
    * type [[handleInteractWith]] ever logs whose identity an anti-message could reference.
    */
  private def findLoggedEventForMessage(messageId: core.actor.rollback.MessageId): Option[core.actor.rollback.LoggedEvent] =
    rollbackHandler.findEvent {
      case interaction: ActorInteractionEvent => interaction.messageId == messageId
      case _                                  => false
    }

  /** Sends a real anti-message retracting `sentMessage`'s original send, to the same receiver via
    * the same routing (`sendMessageToShard`/`sendMessageToPool`) a normal send would use — but
    * carrying the *original* send's `tick`/`seq` (not this actor's current ones), so the receiver
    * can find and undo exactly that send in its own log via [[findLoggedEventForMessage]].
    */
  private def sendAntiMessage(sentMessage: core.actor.rollback.SentMessage): Unit = {
    ActorMetrics.antiMessagesSent.labels(cachedActorTypeLabel).inc()
    val actorType = CreationTypeEnum.valueOf(sentMessage.receiverActorType)
    if (actorType == PoolDistributed) {
      sendMessageToPool(
        entityId = sentMessage.receiverId,
        data = core.actor.rollback.AntiMessagePayload,
        eventType = "anti-message",
        tick = sentMessage.messageId.tick,
        seq = sentMessage.messageId.seq,
        isAntiMessage = true
      )
    } else {
      sendMessageToShard(
        entityId = sentMessage.receiverId,
        shardId = sentMessage.receiverShardId,
        data = core.actor.rollback.AntiMessagePayload,
        eventType = "anti-message",
        tick = sentMessage.messageId.tick,
        seq = sentMessage.messageId.seq,
        isAntiMessage = true
      )
    }
  }

  /** Interns all String fields in the [[relationships]] map to reduce heap duplication.
    * Called once per actor in [[onFinishInitialize]], and again after migration restore.
    * Idempotent — safe to call repeatedly.
    */
  private def internRelationshipsStrings(): Unit =
    if (relationships.nonEmpty) {
      val interned = relationships.iterator.map {
        case (k, v) =>
          StringPool.intern(k) -> ShardActorId(
            entityId    = v.entityId,
            classType   = StringPool.intern(v.classType),
            shardBucket = StringPool.intern(v.shardBucket)
          )
      }.toMap
      relationships.clear()
      relationships ++= interned
    }

  override protected def onFinishInitialize(): Unit = {
    internRelationshipsStrings()
    super.onFinishInitialize()
  }

  /** Gets a specific time manager by type.
    * @param managerType
    *   The type of time manager (e.g., "discrete-event", "time-stepped")
    * @return
    *   The time manager ActorRef, or the default if not found
    */
  protected def getTimeManager(managerType: String): ActorRef =
    timeManagers.getOrElse(managerType, getDefaultTimeManager)

  /** Gets the default time manager (discrete-event).
    * @return
    *   The discrete-event time manager ActorRef
    */
  protected def getDefaultTimeManager: ActorRef =
    timeManagers.getOrElse(TimeManagerTypeEnum.DISCRETE_EVENT, null)

  /** Switches to a different time manager type during simulation. This allows actors to change
    * their time management strategy dynamically.
    *
    * @param newManagerType
    *   The type of time manager to switch to
    * @return
    *   true if switch was successful, false if manager type not available
    */
  protected def switchTimeManager(newManagerType: String): Boolean =
    if (timeManagers != null && timeManagers.contains(newManagerType)) {
      currentTimeManagerType = newManagerType

      registerOnTimeManager()

      logInfo(s"Switched time manager to: $newManagerType")
      true
    } else {
      logWarn(
        s"Time manager type '$newManagerType' not available. Available types: ${properties.getAvailableTimeManagerTypes
            .mkString(", ")}"
      )
      false
    }

  /** Gets the current time manager type being used.
    * @return
    *   The current time manager type name
    */
  protected def getCurrentTimeManagerType: String = currentTimeManagerType
  
  /** Builds a migration snapshot that includes simulation-specific metadata in addition to the base
    * actor state.
    *
    * Captured fields:
    *   - Domain state (via super)
    *   - currentTick, startTick, lamportClock (time/ordering)
    *   - currentTimeManagerType (which time manager was being used)
    *   - dependencies (from_node, to_node, etc. — needed for routing lookups)
    *
    * reporters/timeManagers/creatorManager are NOT serialized here: they are injected via
    * Properties at actor construction time on the target node (cluster-transparent).
    */
  override protected def buildMigrationSnapshot(): MigrationSnapshot = {
    val base = super.buildMigrationSnapshot()

    // Convert relationships to simple string maps for safe serialization
    val depIds = relationships.map {
      case (k, r) => k -> r.entityId.toString
    }.toMap
    val depTypes = relationships.map {
      case (k, r) => k -> r.classType
    }.toMap
    val depResourceIds = relationships.map {
      case (k, r) => k -> r.shardBucket
    }.toMap
    val depActorTypes = relationships.map {
      case (k, _) => k -> ""
    }.toMap

    base.copy(
      currentTick = currentTick,
      startTick = startTick,
      lamportClock = lamportClock.getClock,
      currentTimeManagerType = currentTimeManagerType,
      dependencyIds = depIds,
      dependencyTypes = depTypes,
      dependencyResourceIds = depResourceIds,
      dependencyActorTypes = depActorTypes,
      awaitedReplies = outstandingAwaitedReplies.iterator.map {
        case (key, r) => key -> s"${r.kind}|${r.sentAtTick}|${r.sendSeq}"
      }.toMap
    )
  }

  /** Restores simulation-specific metadata from a migration snapshot.
    *
    * Restores the domain state (via super), then applies:
    *   - currentTick, startTick (simulation time position)
    *   - lamportClock (causal ordering)
    *   - currentTimeManagerType (active time manager)
    *   - dependencies (rebuilt from stored string maps)
    *
    * reporters/timeManagers/creatorManager are already available via Properties (injected at
    * construction time on the target node). They are cluster-transparent ActorRefs and do not need
    * to be serialized across migration.
    */
  override protected def applyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = {
    super.applyMigrationSnapshot(snapshot)

    currentTick = snapshot.currentTick
    startTick = snapshot.startTick
    lamportClock.update(snapshot.lamportClock)
    currentTimeManagerType = snapshot.currentTimeManagerType

    outstandingAwaitedReplies.clear()
    snapshot.awaitedReplies.foreach {
      case (key, encoded) =>
        encoded.split('|') match {
          case Array(kind, sentAtTick, sendSeq) =>
            outstandingAwaitedReplies.put(key, core.actor.AwaitedReply(kind, sentAtTick.toLong, sendSeq.toLong))
          case _ =>
            logWarn(s"$getEntityId: dropping malformed awaited-reply entry '$key' -> '$encoded' from snapshot")
        }
    }

    if (snapshot.dependencyIds.nonEmpty) {
      relationships.clear()
      snapshot.dependencyIds.foreach {
        case (key, id) =>
          val classType = snapshot.dependencyTypes.getOrElse(key, "")
          val shardBucket = snapshot.dependencyResourceIds.getOrElse(key, "")
          relationships.put(
            key,
            ShardActorId(entityId = id.toLong, classType = classType, shardBucket = shardBucket)
          )
      }
    }
  }

  /** True while the actor has queried SM for its migration snapshot and is awaiting reply. All
    * incoming messages are stashed until MigrationContextEvent or NoPendingMigrationEvent arrives.
    */
  private var awaitingMigration: Boolean = false

  override def preStart(): Unit = {
    super.preStart()

    if (properties != null && properties.actorType == LoadBalancedDistributed) {
      entityId = self.path.name
    }

    if (MigrationStateStoreRegistry.isMigrationActive.get()) {
      creatorManager = null
      awaitingMigration = true
      MigrationStateStoreRegistry.getSnapshotManager match {
        case Some(smRef) =>
          smRef ! QueryMigrationEvent(entityId = entityId, actorRef = self)
        case None =>
          logWarn(
            s"Entity '$entityId': migration window active but SM proxy not registered — " +
              s"proceeding with normal initialization."
          )
          awaitingMigration = false
          proceedNormalInit()
      }
      return
    }

    proceedNormalInit()
  }

  /** Normal (non-migration) initialization from Properties.data or onStart(). */
  private def proceedNormalInit(): Unit = {
    if (properties.data != null) {
      try {
        state = JsonUtil.convertValue[T](properties.data)
        if (state != null) {
          startTick = state.getStartTick
        }
        creatorManager ! StartEntityAckEvent(entityId = entityId)
        if (state != null) {
          if (state.isSetScheduleOnTimeManager) {
            registerOnTimeManager()
          } else if (isTimeWarp) {
            registerPassivelyOnTimeManager()
          }
        }
      } catch {
        case e: Exception =>
          logError(s"Error on start actor $entityId: ${e.getMessage}", e)
          e.printStackTrace()
      }
    }
    onStart()
  }

  /** `Identify` for the shard-routed case (`LoadBalancedDistributed`, e.g. `Node`/`Link`/`RailLink`)
    * vs. the direct-ref case, shared by [[registerOnTimeManager]] and [[registerPassivelyOnTimeManager]].
    */
  private def selfIdentify(): Identify =
    Identify(
      id = entityId.toLong,
      resourceId = IdUtil.format(properties.resourceId),
      classType = getClass.getName,
      actorRef = if (properties.actorType == LoadBalancedDistributed) getSelfShard.path.toString else self.path.toString,
      actorType = properties.actorType.toString
    )

  private def registerOnTimeManager(): Unit = {
    if (isTimeWarp) {
      rollbackHandler.initialize(startTick)
    }
    val timeManager = getTimeManager(currentTimeManagerType)
    if (timeManager == null) {
      logWarn(
        s"TimeManager is NULL for $entityId (type=$currentTimeManagerType). timeManagers map: size=${
            if (timeManagers == null) "NULL MAP" else timeManagers.size.toString
          } keys=${if (timeManagers == null) "" else timeManagers.keys.mkString(",")}"
      )
      return
    }
    timeManager ! RegisterActorEvent(startTick = startTick, actorId = entityId.toLong, identify = Some(selfIdentify()))
  }

  /** Registers with the time manager WITHOUT causing any `SpontaneousEvent` dispatch — for actors
    * whose scenario data has `scheduleOnTimeManager = false` (the common case for purely-reactive
    * infrastructure actors: `Link`/`Node`/`RailLink`/`BusStop`/etc., which never had — and must
    * never gain — a periodic dispatch). Only relevant under Time Warp: `report()`'s buffering (§4)
    * means such an actor's reports are otherwise never flushed at all, since `LocalTimeManagerBase.
    * forceDestructActiveActors` (the mechanism that flushes via `onDestruct`) only reaches actors it
    * tracks — and an actor that never registers at all isn't tracked. See `docs/TIME_WARP_DESIGN.md`
    * and [[core.entity.event.control.execution.RegisterPassiveActorEvent]]'s own doc for the full
    * story, including why this can't just reuse `registerOnTimeManager`'s real registration (that
    * path always schedules a real dispatch, which an actor with no real `actSpontaneous` override —
    * e.g. `RailLink` — would mishandle via `handleSpontaneous`'s auto-reschedule safety net,
    * becoming a permanent busy-loop).
    */
  private def registerPassivelyOnTimeManager(): Unit = {
    rollbackHandler.initialize(startTick)
    val timeManager = getTimeManager(currentTimeManagerType)
    if (timeManager == null) return
    timeManager ! core.entity.event.control.execution.RegisterPassiveActorEvent(actorId = entityId.toLong, identify = Some(selfIdentify()))
  }

  override protected def onInitialize(event: InitializeEvent): Unit = {
    entityId = event.id
    if (event.data.timeManagers != null) {
      timeManagers = event.data.timeManagers
      currentTimeManagerType = timeManagers.keys.headOption.getOrElse(TimeManagerTypeEnum.DISCRETE_EVENT)
    } else {
      logWarn(s"onInitialize: timeManagers is NULL for $entityId (class=${getClass.getSimpleName})")
    }
    creatorManager = event.data.creatorManager
    try
      state = JsonUtil.convertValue[T](event.data.data)
    catch {
      case e: Exception =>
        logError(s"Failed to deserialize state for $entityId: ${e.getMessage}", e)
    }
    relationships.clear()
    relationships ++= event.data.relationships
    reporters = event.data.reporters
    if (state != null) {
      startTick = state.getStartTick
      if (state.isSetScheduleOnTimeManager) {
        try
          registerOnTimeManager()
        catch {
          case e: Exception =>
            logError(
              s"$entityId: registerOnTimeManager() FAILED with ${e.getClass.getName}: ${e.getMessage}",
              e
            )
        }
      } else if (isTimeWarp) {
        try
          registerPassivelyOnTimeManager()
        catch {
          case e: Exception =>
            logError(
              s"$entityId: registerPassivelyOnTimeManager() FAILED with ${e.getClass.getName}: ${e.getMessage}",
              e
            )
        }
      }
    } else {
      logWarn(
        s"$entityId: state is NULL after deserialization (class=${getClass.getSimpleName}). Will still send ACK."
      )
    }

    if (requiresPostLoadRegistration && creatorManager != null && state != null) {
      creatorManager ! NeedsPostLoadRegistrationEvent(
        entityId = entityId,
        classType = getClass.getName
      )
    } else if (requiresPostLoadRegistration && state == null) {
      logWarn(
        s"$entityId: skipping NeedsPostLoadRegistrationEvent — state is NULL (class=${getClass.getSimpleName})"
      )
    }
    try
      event.actorRef ! InitializeEntityAckEvent(entityId = entityId)
    catch {
      case e: Exception =>
        logError(
          s"$entityId: FAILED to send InitializeEntityAckEvent: ${e.getClass.getName}: ${e.getMessage}",
          e
        )
    }
    onFinishInitialize()
  }

  /** Return true to opt in to the post-load registration phase. The actor will receive
    * PostLoadRegistrationEvent after all EAGER loading is complete, at which point
    * handlePostLoadRegistration() will be called. The ACK is sent automatically.
    */
  protected def requiresPostLoadRegistration: Boolean = false

  /** Override this (together with requiresPostLoadRegistration = true) to perform any cross-actor
    * registration needed before the simulation starts (e.g. BusStop → Node). The
    * PostLoadRegistrationAckEvent is sent back to the coordinator automatically after this method
    * returns.
    */
  protected def handlePostLoadRegistration(): Unit = {}

  /** Dispatched by BaseActor when PostLoadRegistrationEvent arrives. Calls
    * handlePostLoadRegistration() then sends the ACK back to the coordinator. Subclasses should NOT
    * override this — override handlePostLoadRegistration() instead. ACK is always sent even if
    * handlePostLoadRegistration() throws, so the coordinator never deadlocks waiting for a missing
    * reply.
    */
  override protected final def onPostLoadRegistration(event: PostLoadRegistrationEvent): Unit = {
    try
      handlePostLoadRegistration()
    catch {
      case e: Exception =>
        logError(
          s"$entityId: handlePostLoadRegistration() threw ${e.getClass.getSimpleName}: ${e.getMessage} — sending ACK anyway",
          e
        )
    }
    event.coordinatorRef ! PostLoadRegistrationAckEvent(entityId = entityId)
  }

  /** Sends a message to another simulation actor.
    * @param entityId
    *   The id of the entity in the shard region and simulation
    * @param shardId
    *   The shard id (optional)
    * @param data
    *   The data to send
    * @param eventType
    *   The type of the event
    * @param actorType
    *   The creation type of the target actor
    */
  protected def sendMessageTo(
    entityId: Long,
    shardId: String = null,
    data: AnyRef,
    eventType: String = "default",
    actorType: CreationTypeEnum = LoadBalancedDistributed
  ): Unit = {
    // Replay only rebuilds `state`: the original send of a surviving event was already delivered and
    // is never retracted, so repeating it would hand the receiver a duplicate. Sends of undone
    // events are retracted by anti-message, not resent here.
    if (isReplaying) {
      if (replaySendSeqs.hasNext) replayLastSendSeq = replaySendSeqs.next()
      return
    }
    lamportClock.increment()
    ActorMetrics.messagesSent.labels(getClass.getSimpleName, eventType).inc()
    // Time Warp (docs/TIME_WARP_DESIGN.md §10): a fresh per-actor send identity, generated and
    // written to the wire ONLY under Time Warp. Deliberately not unconditional: proto3 encodes a
    // zero field as zero bytes, so an always-incrementing seq would grow every conservative-mode
    // message's wire size for a field nothing conservative-mode ever reads -- exactly the class of
    // wire-size regression this codebase has a dedicated history of eliminating (see this file's
    // git log). Conservative-mode messages keep seq=0, byte-identical to before this field existed.
    val seq = if (isTimeWarp) {
      messageSendSeq += 1
      currentEventSentMessages += core.actor.rollback.SentMessage(
        messageId = core.actor.rollback.MessageId(senderId = getEntityId, tick = currentTick, seq = messageSendSeq),
        receiverId = entityId,
        receiverShardId = if (shardId != null) shardId else "",
        receiverActorType = actorType.toString
      )
      messageSendSeq
    } else {
      0L
    }
    if (actorType == PoolDistributed) {
      sendMessageToPool(entityId, data, eventType, seq = seq)
    } else {
      sendMessageToShard(entityId, shardId, data, eventType, seq = seq)
    }
  }

  private def sendMessageToShard(
    entityId: Long,
    shardId: String,
    data: AnyRef,
    eventType: String = "default",
    tick: Tick = currentTick,
    seq: Long = 0L,
    isAntiMessage: Boolean = false
  ): Unit = {
    val shardingRegion = getShardRef(IdUtil.format(StringUtil.getModelClassName(shardId)))

    shardingRegion ! core.entity.event.EntityEnvelopeEvent(
      IdUtil.format(entityId),
      ActorInteractionEvent(
        tick = tick,
        lamportTick = getLamportClock,
        actorRefId = getEntityId.toLong,
        shardRefId = IdUtil.format(getShardId),
        actorClassType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
        actorPathRef = self.path.name,
        data = data,
        eventType = eventType,
        actorType = properties.actorType.toString,
        resourceId = properties.resourceId,
        seq = seq,
        isAntiMessage = isAntiMessage
      )
    )
  }

  private def sendMessageToPool(
    entityId: Long,
    data: AnyRef,
    eventType: String = "default",
    tick: Tick = currentTick,
    seq: Long = 0L,
    isAntiMessage: Boolean = false
  ): Unit = {
    val pool = getActorPoolRef(entityId.toString)
    pool ! ActorInteractionEvent(
      tick = tick,
      lamportTick = getLamportClock,
      actorRefId = getEntityId.toLong,
      shardRefId = IdUtil.format(getShardId),
      actorClassType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
      actorPathRef = self.path.name,
      data = data,
      eventType = eventType,
      actorType = properties.actorType.toString,
      resourceId = properties.resourceId,
      seq = seq,
      isAntiMessage = isAntiMessage
    )
  }

  /** Updates the Lamport clock based on another actor's clock value.
    * @param otherClock
    *   The Lamport clock of the other actor
    */
  private def updateLamportClock(otherClock: Long): Unit =
    lamportClock.update(otherClock)

  /** Gets the current Lamport clock value.
    * @return
    *   The current Lamport clock
    */
  private def getLamportClock: Long =
    lamportClock.getClock

  /** Handles spontaneous events triggered by the time manager.
    * @param event
    *   The spontaneous event
    */
  private def handleSpontaneous(event: SpontaneousEvent): Unit = {
    currentTick = event.tick
    currentTimeManager = event.actorRef
    currentGeneration = event.generation
    // §4: this SpontaneousEvent is the one channel an OptimisticLocalTimeManager already has to
    // reach this actor regularly — piggyback its last-known GVT here rather than inventing a
    // separate LTM-to-actor broadcast. An idle actor simply flushes on its next dispatch instead of
    // the instant GVT technically clears it; that's a bounded delay, not a correctness gap (nothing
    // buffered can be observed as "sent" by anyone until it actually is).
    if (isTimeWarp) event.gvt.foreach(flushBufferedReportsUpTo)
    if (state == null) {
      ActorMetrics.eventsWhenStateIsNull.labels(
        getClass.getSimpleName,
        "spontaneous"
      ).inc()
      if (!getEntityId.endsWith("-shard-initiator")) {
        logDebug(
          s"handleSpontaneous called with null state at tick=$currentTick for ${getEntityId} — unscheduling"
        )
      }
      onFinishSpontaneous(None)
      return
    }
    _spontaneousFinishSent = false
    if (isTimeWarp) {
      currentEventSentMessages.clear()
      currentEventDynamicWeightReads.clear()
      currentEventScheduleOutcome = None
    }
    try
      if (isTimeWarp) DynamicWeightCache.withRecording(currentEventDynamicWeightReads)(actSpontaneous(event))
      else actSpontaneous(event)
    catch
      case e: Throwable =>
        logError(
          s"Exception during actSpontaneous at tick=$currentTick for ${getEntityId}: ${e.getMessage}"
        )
        e.printStackTrace()
        if (state == null) onFinishSpontaneous(None)
        else onFinishSpontaneous(Some(currentTick + 1))
    if (!_spontaneousFinishSent) {
      logError(
        s"[BUG] ${getClass.getSimpleName}/${getEntityId} did not call onFinishSpontaneous at tick=$currentTick — auto-recovering"
      )
      onFinishSpontaneous(Some(currentTick + 1))
    }
    if (isTimeWarp) {
      processedEventSeq += 1
      rollbackHandler.recordProcessedEvent(
        event,
        tick = currentTick,
        seq = processedEventSeq,
        sentMessages = currentEventSentMessages.toSeq,
        dynamicWeightReads = currentEventDynamicWeightReads.toMap,
        scheduleOutcome = currentEventScheduleOutcome
      )
    }
  }

  /** Called when the actor receives a spontaneous event from the time manager. Override this method
    * to handle spontaneous events.
    * @param event
    *   The spontaneous event
    */
  protected def actSpontaneous(event: SpontaneousEvent): Unit = ()

  /** Handles interaction events from other actors.
    * @param event
    *   The interaction event
    */
  private def handleInteractWith(event: ActorInteractionEvent): Unit = {
    ActorMetrics.eventsProcessed.labels("interaction").inc()
    updateLamportClock(event.lamportTick)

    // Time Warp anti-message receipt (docs/TIME_WARP_DESIGN.md §10): not a real interaction to
    // process via actInteractWith at all -- find the original send this retracts and undo back to
    // it, cascading further anti-messages for whatever that undoes. If the original can't be
    // found (already fossil-collected below GVT, or this actor was never the receiver -- shouldn't
    // happen but isn't fatal either way), there's nothing left to undo; log and move on.
    if (isTimeWarp && event.isAntiMessage) {
      findLoggedEventForMessage(event.messageId) match {
        case Some(logged) =>
          ActorMetrics.antiMessageRollbackTriggered.labels(cachedActorTypeLabel).inc()
          val undone = rollbackAndCascade(logged.tick)
          interactionsToReprocess(undone, annihilated = Some(event.messageId)).foreach(handleInteractWith)
        case None =>
          // Not necessarily "pruned" -- RollbackHistoryHandler.pruneBelow is dead code today
          // (never called), so GVT-based fossil collection never actually happens. This branch
          // means either an earlier overlapping rollback already retracted/removed the logged
          // event this anti-message refers to, or a genuine protocol anomaly.
          logWarn(
            s"$getEntityId: anti-message for unknown messageId=${event.messageId} " +
              s"(already retracted by an earlier overlapping rollback, or a genuine protocol anomaly)"
          )
      }
      return
    }

    // Time Warp straggler detection: a real causality violation -- this interaction's tick is
    // behind what this actor already processed, meaning it ran ahead of where it should have.
    // Roll back to before it, cascading anti-messages for whatever that undoes, THEN process this
    // event normally below (now in its causally-correct position) -- Jefferson's "roll back to
    // before the violation, then process it in order," not a special second code path.
    //
    // Exception, checked first (docs/KNOWN_GAPS.md's Cause 3, found 2026-09-11): a tick-behind
    // message that a subclass can PROVE is a reply to a request it has since superseded with a
    // fresh one (via the same AwaitedReply.sendSeq identity check `isAwaitingReply` already uses
    // deeper in business logic, e.g. CarLinkHandler's leave-link staleness gate) carries no
    // information this actor's current state doesn't already correctly reflect -- its content
    // will never be acted on regardless of whether we roll back first. Paying the full rollback +
    // anti-message-cascade cost just to reach that same "discard, do nothing" conclusion is pure
    // waste, and a real live run showed this exact case (`Link -> Car ReceiveEnterLinkInfo`)
    // dominating rollback volume. Dropping it here short-circuits BEFORE `actInteractWith` -- unlike
    // a handler-level staleness gate, which only helps once the (expensive) rollback has already
    // happened. Default `isKnownSupersededReply` is `false` for every actor that doesn't override
    // it, so this changes nothing for anyone who hasn't opted in.
    var undoneInteractions: Seq[ActorInteractionEvent] = Seq.empty
    if (isTimeWarp && event.tick < currentTick) {
      if (isKnownSupersededReply(event)) {
        ActorMetrics.staleReplyDroppedWithoutRollback
          .labels(cachedActorTypeLabel, event.actorClassType, event.eventType)
          .inc()
        logDebug(
          s"$getEntityId: dropping known-superseded reply without a rollback: " +
            s"eventType=${event.eventType} from=${event.actorClassType} eventTick=${event.tick} currentTick=$currentTick"
        )
        return
      }
      // Root-cause visibility (docs/KNOWN_GAPS.md's Cause 3, 2026-09-11): who caused whom to roll
      // back, by what message, and how far this actor had incorrectly raced ahead. See
      // ActorMetrics.stragglerRollbackTriggered's doc for why this matters more than just "how much
      // gets discarded."
      ActorMetrics.stragglerRollbackTriggered
        .labels(cachedActorTypeLabel, event.actorClassType, event.eventType)
        .inc()
      ActorMetrics.stragglerRollbackDistanceTicks
        .labels(cachedActorTypeLabel)
        .observe((currentTick - event.tick).toDouble)
      undoneInteractions = interactionsToReprocess(rollbackAndCascade(event.tick), annihilated = None)
    }

    if (event.tick > currentTick) {
      currentTick = event.tick
    }
    if (isTimeWarp) {
      currentEventSentMessages.clear()
      currentEventDynamicWeightReads.clear()
      currentEventScheduleOutcome = None
    }
    try
      if (isTimeWarp) DynamicWeightCache.withRecording(currentEventDynamicWeightReads)(actInteractWith(event))
      else actInteractWith(event)
    catch
      case e: Exception =>
        logError(
          s"Exception during actInteractWith at tick=$currentTick for ${getEntityId} " +
            s"from ${event.actorRefId} (${event.eventType}): ${e.getMessage}"
        )
        e.printStackTrace()
    if (isTimeWarp) {
      processedEventSeq += 1
      rollbackHandler.recordProcessedEvent(
        event,
        tick = currentTick,
        seq = processedEventSeq,
        sentMessages = currentEventSentMessages.toSeq,
        dynamicWeightReads = currentEventDynamicWeightReads.toMap,
        scheduleOutcome = currentEventScheduleOutcome
      )
    }
    // After the straggler, so they run in causal order. Only the straggler branch fills this.
    undoneInteractions.foreach(handleInteractWith)
  }

  /** Called when the actor receives an interaction event from another actor. Override this method
    * to handle interactions.
    * @param event
    *   The interaction event
    */
  def actInteractWith(event: ActorInteractionEvent): Unit = ()

  override def receive: Receive = {
    case event: MigrationContextEvent if awaitingMigration =>
      awaitingMigration = false
      handleMigrationContext(event)
      unstashAll()

    case _: NoPendingMigrationEvent if awaitingMigration =>
      awaitingMigration = false
      unstashAll()
      proceedNormalInit()

    case _ if awaitingMigration =>
      stash()

    case event: MigrationContextEvent      => handleMigrationContext(event)
    case event: SpontaneousEvent           => handleSpontaneous(event)
    case event: ActorInteractionEvent      => handleInteractWith(event)
    case event: ShardRegion.StartEntityAck => handleSpawnedEntityStartAck(event)
    case event: InitializeEntityAckEvent   => handleSpawnedEntityInitAck(event)
    case event                             => super.receive(event)
  }

  /** Returns true if this entity should re-register on the TimeManager after migration restore.
    *
    * Default: mirrors the static `scheduleOnTimeManager` config flag. Subclasses that dynamically
    * unregister/re-register during their lifecycle (e.g. Person during vehicle trips) should
    * override this to reflect runtime state.
    */
  protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean =
    state != null && state.isSetScheduleOnTimeManager

  /** Spawns a dynamic sharded actor using the 2-phase creation protocol that mirrors
    * CreatorLoadData: (1) ShardRegion.StartEntity → (2) StartEntityAck → (3) send
    * EntityEnvelopeEvent(InitializeEvent) → (4) receive InitializeEntityAckEvent.
    *
    * The shard region for `classType` must be pre-registered on all nodes (via dynamicActorTypes
    * in the scenario config). Override [[onDynamicActorInitialized]] to react when the new entity
    * finishes initialization.
    */
  protected def spawnDynamicActor(
    classType: String,
    entityId: Long,
    stateData: Any,
    relationships: mutable.Map[String, ShardActorId] = mutable.Map.empty
  ): Unit = {
    val initEvent = InitializeEvent(
      id = entityId.toString,
      actorRef = self,
      data = InitializeData(
        data = stateData,
        resourceId = properties.resourceId,
        timeManagers = timeManagers,
        creatorManager = self,
        reporters = reporters,
        relationships = relationships
      )
    )
    val shardRegion = createShardRegion(
      system = context.system,
      actorClassName = classType,
      entityId = entityId.toString,
      resourceId = properties.resourceId,
      timeManagers = timeManagers,
      creatorManager = creatorManager,
      reporters = reporters
    )
    pendingDynamicInits.put(entityId.toString, (shardRegion, initEvent))
    dynamicActorClassTypes.put(entityId.toString, classType)
    shardRegion ! ShardRegion.StartEntity(entityId.toString)
  }

  /** Called when a dynamically spawned actor has finished initialization (received
    * InitializeEntityAckEvent). Default is a no-op; override to perform post-creation logic.
    */
  protected def onDynamicActorInitialized(entityId: String, classType: String): Unit = {}

  private def handleSpawnedEntityStartAck(event: ShardRegion.StartEntityAck): Unit =
    pendingDynamicInits.remove(event.entityId) match {
      case Some((shardRegion, initEvent)) =>
        shardRegion ! EntityEnvelopeEvent(event.entityId, initEvent)
      case None =>
        logWarn(s"StartEntityAck for untracked entityId ${event.entityId} — ignoring")
    }

  private def handleSpawnedEntityInitAck(event: InitializeEntityAckEvent): Unit = {
    val classType = dynamicActorClassTypes.remove(event.entityId).getOrElse("")
    onDynamicActorInitialized(event.entityId, classType)
  }

  /** For cluster-sharded (LoadBalancedDistributed) entities, use Pekko passivation instead of
    * context.stop(self). Passivation signals the ShardRegion to stop buffering messages for this
    * entity ID, so stale in-flight messages go to Dead Letters instead of triggering a ghost
    * restart with state == null. Plain context.stop does NOT inform the shard, causing the entity
    * to be recreated on the next incoming message.
    *
    * Also the real termination path for Time Warp participants (`Car`/`Bicycle`/`Motorcycle`/
    * `PrivateVehicle`/`Movable` all call this directly, not `onDestruct`'s `DestructEvent` path) —
    * so any report() still sitting in [[reportBuffer]] waiting for a GVT that will now never arrive
    * (this actor is about to stop existing) must flush unconditionally here. §4: nothing can ever
    * roll back an actor that no longer exists, so every remaining buffered report is safe by
    * construction at this point, not just the ones already covered by the last GVT this actor saw.
    */
  override protected def selfDestruct(): Unit = {
    if (isTimeWarp) flushBufferedReportsUpTo(Long.MaxValue)
    if (properties != null && properties.actorType == LoadBalancedDistributed) {
      context.parent ! ShardRegion.Passivate(PoisonPill)
    } else {
      context.stop(self)
    }
  }

  /** Same unconditional final flush as [[selfDestruct]]'s doc explains, for the other termination
    * path: forced destruction via a `DestructEvent` from the time manager (`BaseActor.destruct`)
    * rather than the actor choosing to stop itself.
    */
  override protected def onDestruct(event: DestructEvent): Unit = {
    if (isTimeWarp) flushBufferedReportsUpTo(Long.MaxValue)
    super.onDestruct(event)
  }

  private def handleMigrationContext(event: MigrationContextEvent): Unit = {
    logInfo(
      s"Entity '$entityId' received MigrationContextEvent: " +
        s"timeManagers=${event.timeManagers.keys.mkString(",")}, " +
        s"reporters=${event.reporters.size}, batchId=${event.batchId}"
    )
    applyMigrationSnapshot(event.snapshot)

    if (event.snapshot.entityId.nonEmpty) {
      entityId = event.snapshot.entityId
    }

    if (event.timeManagers.nonEmpty) timeManagers = event.timeManagers
    if (event.reporters.nonEmpty) reporters = event.reporters
    creatorManager = null

    if (shouldRegisterOnTimeManagerAfterMigration()) {
      registerOnTimeManager()
    }
    onFinishInitialize()

    if (event.lbmRef != null) {
      event.lbmRef ! MigrationRestoredAckEvent(entityId = entityId, batchId = event.batchId)
    }

    onMigrationRestore()
  }

  /** Finishes processing a spontaneous event and optionally schedules the next tick.
    * @param scheduleTick
    *   Optional tick to schedule next event
    * @param destruct
    *   Whether to destroy the actor after finishing
    */
  /** Signals that onFinishSpontaneous will be called later (e.g., from onDynamicActorInitialized
    * after async ACKs arrive). Suppresses the safety-net without sending a FinishEvent.
    */
  protected def deferFinishSpontaneous(): Unit =
    _spontaneousFinishSent = true

  protected def onFinishSpontaneous(
    scheduleTick: Option[Tick] = None,
    destruct: Boolean = false
  ): Unit = {
    // Time Warp replay (see isReplaying's doc): actSpontaneous calling this during
    // replayLoggedEvent must not re-signal the time manager -- that signal already happened, for
    // real, the first time this event was live-dispatched. Nothing to update here either:
    // _spontaneousFinishSent's safety-net check only runs inside handleSpontaneous, which replay
    // never goes through.
    if (isReplaying) return
    _spontaneousFinishSent = true
    if (isTimeWarp) currentEventScheduleOutcome = Some(scheduleTick)
    // currentTimeManager is only ever set by a real live SpontaneousEvent dispatch (handleSpontaneous/
    // replayLoggedEvent). Found via the conservative-vs-optimistic comparison run (docs/
    // TIME_WARP_DESIGN.md): a passively-registered Time Warp actor (RegisterPassiveActorEvent --
    // never dispatched a SpontaneousEvent by design) can still reach this method indirectly during
    // onDestruct's business-logic cleanup (e.g. Car.onDestruct -> PrivateVehicle.deactivateVehicle
    // -> scheduleNextTick -> here), with currentTimeManager still null -- there was never a live TM
    // turn to "finish." Nothing to legitimately report to in that case; skip the send rather than
    // NPE or route a FinishEvent to some pool instance that never dispatched this actor in the first
    // place (which could phantom-schedule it for a future tick it'll never see, since it's mid-destruct).
    if (currentTimeManager == null) {
      logDebug(s"$getEntityId: onFinishSpontaneous called with no currentTimeManager (never live-dispatched) -- nothing to report, skipping")
      return
    }
    currentTimeManager ! FinishEvent(
      end = currentTick,
      actorRef = self,
      identify = Identify(
        id = getEntityId.toLong,
        resourceId = IdUtil.format(properties.resourceId),
        classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
        actorRef = getPath,
        actorType = properties.actorType.toString
      ),
      scheduleTick = scheduleTick.map(_.toString),
      scheduleEvent = None,
      timeManager = currentTimeManager,
      destruct = destruct,
      generation = currentGeneration,
      awaitingReply = isTimeWarp && scheduleTick.isEmpty && outstandingAwaitedReplies.nonEmpty
    )
  }

  /** Sends a spontaneous event to itself. */
  protected def selfSpontaneous(): Unit =
    self ! SpontaneousEvent(currentTick, currentTimeManager)

  /** Schedules an event at a specific tick. CRITICAL: Send to currentTimeManager (same TM that sent
    * last SpontaneousEvent), NOT to the pool router. Using getTimeManager() routes round-robin to
    * any pool member; if that member differs from currentTimeManager, the ScheduleEvent and
    * FinishEvent(None) go to different TMs. When all TMs (including currentTimeManager) report
    * hasScheduled=false, the global TM terminates the simulation — even though another TM has the
    * actor scheduled.
    * @param tick
    *   The tick at which the event should be scheduled
    */
  protected def scheduleEvent(tick: Tick, resyncAfterRollback: Boolean = false): Unit = {
    // Time Warp replay: same reasoning as onFinishSpontaneous's isReplaying guard -- this actor's
    // real future schedule was already correctly registered with the time manager the first time
    // this event was live-dispatched; replaying it must not re-register (or double-register) it.
    if (isReplaying) return
    if (isTimeWarp && !resyncAfterRollback) currentEventScheduleOutcome = Some(Some(tick))
    val tm =
      if (currentTimeManager != null) currentTimeManager else getTimeManager(currentTimeManagerType)
    tm ! ScheduleEvent(
      tick = tick,
      actorRef = getPath,
      identify = Some(
        Identify(
          id = getEntityId.toLong,
          resourceId = IdUtil.format(properties.resourceId),
          classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
          actorRef = getPath,
          actorType = properties.actorType.toString
        )
      ),
      resyncAfterRollback = resyncAfterRollback
    )
  }

  /** Reports data to the reporting system.
    * @param data
    *   The data to report
    * @param label
    *   Optional label for the report
    */
  protected def report(data: Any, label: String = null): Unit =
    report(
      event = ReportEvent(
        entityId = entityId,
        tick = currentTick,
        lamportTick = getLamportClock,
        data = data,
        label = label
      )
    )

  /** Default report strategy resolved once per actor and cached. Avoids a [[config.getString]] +
    * [[ReportTypeEnum.valueOf]] allocation on every call to [[report]] (which can be invoked
    * thousands of times per simulation tick across the whole actor population).
    */
  private lazy val cachedDefaultReportType: ReportTypeEnum =
    try ReportTypeEnum.valueOf(config.getString("htc.report-manager.default-strategy"))
    catch { case _: Exception => ReportTypeEnum.valueOf("csv") }

  /** This actor's own model class type (e.g. "Car"), resolved once and cached for Prometheus
    * labels -- avoids a `getClass.getName` + string-split allocation on every interaction/
    * anti-message under Time Warp's rollback-metrics instrumentation.
    */
  private lazy val cachedActorTypeLabel: String = StringUtil.getModelClassNameWithoutPackage(getClass.getName)

  /** Reports an event to the reporting system.
    *
    * Under Time Warp (`docs/TIME_WARP_DESIGN.md` §4), the actual send to `target` is deferred:
    * report() may be called by execution that a later rollback undoes, and once sent, a report can
    * never be un-sent from the reporter pipeline (ClickHouse/Kafka/Prometheus). The target reporter
    * ref is resolved now (state may look different by flush time) and carried alongside the buffered
    * event; only the send itself waits for [[flushBufferedReportsUpTo]] to confirm GVT has passed
    * this event's tick. The `eventsProcessed` metric still increments immediately either way — it
    * counts this actor processing an event, not data reaching a reporter.
    * @param event
    *   The report event
    */
  protected def report(event: ReportEvent): Unit = {
    // The surviving event's original report is still in reportBuffer (rollback only drops entries
    // at or after the target tick); replaying it again would emit it twice.
    if (isReplaying) return
    if (reporters.isEmpty) return
    if (event.label != null) {
      ActorMetrics.eventsProcessed.labels(event.label).inc()
    }
    val stateReporter = state.getReporterType
    val reportType = if (stateReporter != null) stateReporter else cachedDefaultReportType
    val target = if (reporters.contains(reportType)) reporters(reportType) else reporters(cachedDefaultReportType)
    if (isTimeWarp) {
      reportBuffer += ((event, target))
    } else {
      if (event.label != null) ActorMetrics.reportFlushed.labels(event.label).inc()
      target ! event
    }
  }

  /** Sends every buffered report whose tick is now covered by `gvt` — §4's "safe, can never be
    * rolled back" watermark — and leaves everything else buffered for a later call. Called from
    * [[handleSpontaneous]] whenever a dispatch carries a fresh GVT estimate, and unconditionally
    * (passing `Long.MaxValue`) from [[selfDestruct]]/`onDestruct` since nothing can roll back an
    * actor that no longer exists.
    */
  private def flushBufferedReportsUpTo(gvt: Tick): Unit =
    if (reportBuffer.nonEmpty) {
      val (ready, pending) = reportBuffer.partition(_._1.tick <= gvt)
      if (ready.nonEmpty) {
        reportBuffer.clear()
        reportBuffer ++= pending
        ready.foreach {
          case (event, target) =>
            if (event.label != null) ActorMetrics.reportFlushed.labels(event.label).inc()
            target ! event
        }
      }
    }

  /** Reports data without a label.
    * @param data
    *   The data to report
    */
  protected def report(data: Any): Unit = {
    val event = ReportEvent(
      entityId = entityId,
      tick = currentTick,
      lamportTick = getLamportClock,
      data = data
    )
    report(event)
  }

  /** Sends an event directly to a specific reporter type if registered. No-op if that reporter is
    * not in the enabled strategies — simulation continues without any error or performance penalty.
    */
  protected def reportToSpecificReporter(
    reportType: ReportTypeEnum,
    data: Any,
    label: String = null
  ): Unit =
    reporters.get(reportType).foreach {
      reporter =>
        reporter ! ReportEvent(
          entityId = entityId,
          tick = currentTick,
          lamportTick = getLamportClock,
          data = data,
          label = label
        )
    }

  /** Reports to the default strategy (e.g. Parquet) and mirrors the same payload to the `kafka`
    * reporter for live consumers (htc-play), if and only if `kafka` is present in
    * `htc.report-manager.enabled-strategies`. No-op mirror otherwise — see
    * [[reportToSpecificReporter]]. `KafkaReportData`/`KafkaPublisher` are themselves fail-safe if
    * Redpanda is unreachable, so this call never affects simulation correctness or liveness
    * regardless of whether Redpanda is configured, running, or reachable.
    */
  protected def reportMirrored(data: Any, label: String = null): Unit = {
    report(data = data, label = label)
    reportToSpecificReporter(ReportTypeEnum.kafka, data, label)
  }

  /** Gets a relationship by entity id.
    * @param entityId
    *   The entity id
    * @return
    *   The relationship
    */
  protected def getRelationship(entityId: String): ShardActorId = {
    val formattedId = IdUtil.format(entityId)
    relationships.get(formattedId) match {
      case Some(relationship) => relationship
      case None =>
        logWarn(
          s"ShardActorId not found for entityId: $entityId (formatted: $formattedId). Available relationships: ${relationships.keys
              .mkString(", ")}"
        )
        throw new NoSuchElementException(s"ShardActorId not found: $entityId")
    }
  }

  /** Safely gets a relationship by entity id, returning None if not found.
    * @param entityId
    *   The entity id
    * @return
    *   The relationship wrapped in Option
    */
  protected def getRelationshipOption(entityId: String): Option[ShardActorId] = {
    val formattedId = IdUtil.format(entityId)
    relationships.get(formattedId)
  }

  /** Safely gets a relationship by entity id, throwing exception if not found.
    * @param entityId
    *   The entity id
    * @return
    *   The relationship
    * @throws NoSuchElementException
    *   if relationship not found
    */
  protected def getRelationshipSafe(entityId: String): ShardActorId =
    getRelationshipOption(entityId) match {
      case Some(relationship) => relationship
      case None =>
        logError(s"ShardActorId not found: $entityId")
        throw new NoSuchElementException(s"ShardActorId not found: $entityId")
    }

  /** @deprecated Use getRelationship instead. */
  @deprecated("Use getRelationship instead", "2.8.0")
  protected def getDependency(entityId: String): ShardActorId = getRelationship(entityId)

  /** @deprecated Use getRelationshipOption instead. */
  @deprecated("Use getRelationshipOption instead", "2.8.0")
  protected def getDependencyOption(entityId: String): Option[ShardActorId] = getRelationshipOption(
    entityId
  )

  /** @deprecated Use getRelationshipSafe instead. */
  @deprecated("Use getRelationshipSafe instead", "2.8.0")
  protected def getDependencySafe(entityId: String): ShardActorId = getRelationshipSafe(entityId)

  /** Gets the time manager actor reference (default: discrete-event).
    * @return
    *   The time manager actor reference
    */
  protected def getTimeManager: ActorRef = getDefaultTimeManager
}
