package org.interscity.htc
package core.actor

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.cluster.sharding.ShardRegion
import core.actor.manager.loadbalance.migration.{MigrationSnapshot, MigrationStateStoreRegistry}
import core.api.SimulatorSettingsRegistry
import core.entity.event.{ActorInteractionEvent, FinishEvent, SpontaneousEvent}
import core.entity.event.control.migration.{MigrationContextEvent, MigrationPreparationFailedEvent, MigrationRestoredAckEvent, MigrationRestoreAlreadyAcceptedEvent, MigrationRestoreFailedEvent, NoPendingMigrationEvent, QueryMigrationEvent}
import core.runtime.{EventOrder, OrderedInputQueue}
import core.runtime.accounting.*
import core.runtime.conservative.*
import core.entity.event.control.execution.ReplaceScheduleEvent
import core.types.Tick
import core.entity.state.BaseState
import core.entity.control.LamportClock
import core.util.{IdUtil, JsonUtil, StringPool, StringUtil}

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, RegisterActorEvent}
import org.htc.protobuf.core.entity.event.control.load.{InitializeEntityAckEvent, StartEntityAckEvent}
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.{InitializeEvent, NeedsPostLoadRegistrationEvent, PostLoadRegistrationAckEvent, PostLoadRegistrationEvent}
import org.interscity.htc.core.entity.event.control.report.ReportEvent
import org.interscity.htc.core.enumeration.{ReportTypeEnum, TimeManagerTypeEnum}
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.{LoadBalancedDistributed, PoolDistributed}
import org.interscity.htc.core.metrics.core.ActorMetrics

import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized
import core.entity.event.EntityEnvelopeEvent
import core.util.ActorCreatorUtil.createShardRegion
import core.entity.event.data.InitializeData

import org.interscity.htc.core.entity.event.control.rollback
import org.interscity.htc.core.entity.event.control.rollback.{AntiMessageCascade, AntiMessagePayload, EventReadJournal, LoggedEvent, MessageId, RecordedRead, RollbackHistoryHandler, SentMessage}

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
  private var scheduleRevision = 0L
  private var accountedCoordinator: Option[ActorRef] = Option(properties).flatMap(_.runtimeCoordinator)
  private var runtimeEpoch: Option[String] = None
  private val runtimeIncarnation = java.util.UUID.randomUUID().toString
  private var runtimeFrozen = false
  private var distributedRuntime = false
  private var runtimeHorizon = Long.MaxValue
  private var executionLimits: Option[SetOptimisticExecutionLimits] = None
  private case object ContinueOptimisticExecution
  private var continuationQueued = false
  private var runtimeGvt = Long.MinValue
  private var runtimeCommitRound = 0L
  private var committedRetirement: Option[(Long, Boolean)] = None
  private var runtimeFrontierAt = System.nanoTime()
  private var runtimeDiagnosticAt = 0L
  private var runtimeFailed = false
  private var terminalIntent: Option[EventOrder] = None
  private var runtimeBirth: Option[RuntimeBirthOrigin] = None
  private var runtimeBorn = true
  // Rollback can erase the latest input while its peer still owes a later cancellation.
  // This actor-lifetime receipt fence must never rewind with the model checkpoint.
  private var retirementFence = Long.MinValue
  private val pendingCancellations = mutable.Queue.empty[ActorInteractionEvent]
  private val initializationAcks = mutable.ArrayBuffer.empty[(ActorRef, AnyRef)]
  private val pendingCreationCallbacks = mutable.Queue.empty[(String, Long)]
  protected def usesAccountedRuntime: Boolean = accountedCoordinator.nonEmpty
  private def usesConservativeRuntime: Boolean = usesAccountedRuntime && !isTimeWarp
  private def runtimeAuthority: Option[ActorRef] =
    if (distributedRuntime) Some(currentTimeManager) else accountedCoordinator

  /** Reject model capabilities whose effects cannot participate in the runtime's cut. */
  protected def validateRuntimeCapabilities(): Unit = ()

  private def sendTimeManager(target: ActorRef, event: AnyRef): Unit = accountedCoordinator match {
    case Some(_) if usesConservativeRuntime || distributedRuntime => target ! RouteControl(runtimeEpoch.get, target, event, math.max(currentTick, runtimeGvt))
    case Some(coordinator) => coordinator ! RouteControl(runtimeEpoch.get, target, event, math.max(currentTick, runtimeGvt))
    case None => target ! event
  }

  private def failRuntime(error: Throwable): Unit = {
    runtimeFailed = true
    runtimeFrozen = true
    accountedCoordinator.foreach(_ ! RuntimeFailure(s"${getClass.getName}/$getEntityId: ${error.getMessage}"))
  }

  private var logicalOrder: Option[EventOrder] = None
  private var pendingSelf: Option[EventOrder] = None
  private var effectOrdinal = 0L
  private var horizonReporting: Option[Tick] = None
  private var finalizedHorizon: Option[Tick] = None
  private val inputQueue = new OrderedInputQueue
  private var drainingInputs = false

  private def nextEffectOrder(at: Tick): EventOrder = {
    effectOrdinal = Math.addExact(effectOrdinal, 1L)
    logicalOrder.getOrElse(EventOrder.root(currentTick, getClass.getName, getEntityId.toLong))
      .child(at, effectOrdinal)
  }

  private def nextScheduleRevision(): Long = {
    scheduleRevision = Math.addExact(scheduleRevision, 1L)
    scheduleRevision
  }

  private def enqueueInput(event: AnyRef): Unit = {
    inputQueue.enqueue(event)
    drainInputs()
  }

  private def drainInputs(): Unit = {
    if (drainingInputs || runtimeFrozen || runtimeFailed || (usesAccountedRuntime && runtimeEpoch.isEmpty)) return
    drainingInputs = true
    val turnBudget = executionLimits.fold(Int.MaxValue)(_.limits.maxEventsPerTurn)
    var executed = 0
    try {
      var cancelling = true
      while (executed < turnBudget && !runtimeFailed && cancelling) {
        pendingCancellations.dequeueFirst(_.tick <= runtimeHorizon) match {
          case Some(event) => handleInteractWith(event); executed += 1
          case None => cancelling = false
        }
      }
      while (pendingCreationCallbacks.nonEmpty && !runtimeFailed) {
        val (id, _) = pendingCreationCallbacks.dequeue()
        val classType = dynamicActorClassTypes.remove(id).getOrElse("")
        onDynamicActorInitialized(id, classType)
      }
      var ready = true
      while (!runtimeFailed && ready && inputQueue.nonEmpty && executed < turnBudget) {
        // Transport release cannot wait behind a model input that needs the replacement slot.
        inputQueue.discardDispatchesWhere(e =>
          (e.order.nonEmpty && e.scheduleRevision < scheduleRevision) ||
            (e.generation > 0L && e.generation <= currentGeneration)).foreach(handleSpontaneous)
        val head = inputQueue.headOption
        val headOrder = head.flatMap {
          case e: ActorInteractionEvent => e.order
          case e: SpontaneousEvent => e.order
          case _ => None
        }
        val headTick = head.map {
          case e: ActorInteractionEvent => e.tick
          case e: SpontaneousEvent => e.tick
        }
        val withinLimits = headTick.forall(tick => tick <= runtimeHorizon && executionLimits.forall { granted =>
          tick <= granted.ceiling && (tick <= granted.frontier ||
            rollbackHandler.uncommittedEventCount < granted.limits.maxUncommittedEvents)
        })
        val birthReady = head.forall {
          case e: ActorInteractionEvent if cancelledMessages.contains(e.messageId) || processedMessages.contains(e.messageId) => true
          case e: ActorInteractionEvent if e.data.isInstanceOf[RuntimeBirthData] => !runtimeBorn
          case _ => runtimeBorn
        }
        ready = head.nonEmpty && birthReady && withinLimits && !pendingSelf.exists(p => headOrder.exists(p < _))
        if (ready) {
          inputQueue.dequeue() match {
            case e: ActorInteractionEvent => handleInteractWith(e)
            case e: SpontaneousEvent => handleSpontaneous(e)
            case _ => ()
          }
          executed += 1
        }
      }
    } finally drainingInputs = false
    if (executed >= turnBudget && !runtimeFailed && !continuationQueued &&
      (inputQueue.nonEmpty || pendingCancellations.nonEmpty)) {
      continuationQueued = true
      self ! ContinueOptimisticExecution
    }
  }


  private val pendingDynamicInits: mutable.Map[String, (ActorRef, InitializeEvent)] = mutable.Map.empty
  private val dynamicActorClassTypes: mutable.Map[String, String] = mutable.Map.empty

  // This actor's own monotonically increasing processed-event counter — the `seq` RollbackHistoryHandler
  // orders its log by (see LoggedEvent.seq's doc). A plain actor-local `var`, not `state`: safe per
  // CLAUDE.md's rule for actor-local mutable fields, since it holds no reply obligation and is
  // cheap to recompute/reset — it only orders this actor's own already-in-memory replay log, not
  // anything another actor is waiting to hear back about.
  private var processedEventSeq: Long = 0L
  private var currentEventSeq: Long = 0L

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
  private val currentEventSentMessages: mutable.ArrayBuffer[SentMessage] = mutable.ArrayBuffer.empty

  // A sequence preserves repeated reads of the same external input within one event.
  private val currentEventReads: mutable.ArrayBuffer[RecordedRead] = mutable.ArrayBuffer.empty

  // Transport cancellation survives a state rewind, including when the positive arrives later.
  private val cancelledMessages: mutable.Set[MessageId] = mutable.Set.empty
  private val processedMessages: mutable.Set[MessageId] = mutable.Set.empty

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

  // Both policies need this to distinguish an idle actor from an unresolved interaction.
  // Tracks "am I genuinely still waiting for a specific reply" alongside `state`.
  // Part of the migration snapshot, so a checkpoint restore plus replay rebuilds it exactly as it was
  // at the restored point: waits registered by undone events disappear, and a wait an undone event
  // consumed comes back. A fresh registerAwaitedReply for the same key supersedes (overwrites) any
  // prior entry, so "stale" means "a newer request for the same thing has since been sent."
  private val outstandingAwaitedReplies: mutable.Map[String, core.actor.AwaitedReply] = mutable.Map.empty

  /** Overwrites any existing entry for `key` -- see
    * [[outstandingAwaitedReplies]]'s doc for why a fresh registration superseding a stale one is
    * the intended behavior, not a bug.
    */
  protected def registerAwaitedReply(key: String, kind: String): Unit = {
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
    isTimeWarp && matchesAwaitedReply(key, kind, requestSeq)

  protected def matchesAwaitedReply(key: String, kind: String, requestSeq: Long): Boolean =
    outstandingAwaitedReplies.get(key).exists(r => r.kind == kind && r.sendSeq == requestSeq)

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
  private final case class BufferedReport(event: ReportEvent, target: ActorRef, eventSeq: Long)
  private val reportBuffer: mutable.ArrayBuffer[BufferedReport] = mutable.ArrayBuffer.empty

  /** Coast-forward reconstructs a surviving prefix without repeating its external effects.
    * Undone events run live again with fresh send identities after cancellation.
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
    * [[EventReadJournal]] reproduces the original event's external observations in read order.
    * A replay failure propagates: continuing with partially reconstructed state is incorrect.
    */
  private def replayLoggedEvent(logged: LoggedEvent): Unit = {
    isReplaying = true
    replaySendSeqs = logged.sentMessages.iterator.map(_.messageId.seq)
    replayLastSendSeq = -1L
    try
      EventReadJournal.withReplay(logged.recordedReads) {
        logged.event match {
          case spontaneous: SpontaneousEvent =>
            currentTick = spontaneous.tick
            logicalOrder = spontaneous.order
            effectOrdinal = 0L
            pendingSelf = None
            actSpontaneous(spontaneous)
          case interaction: ActorInteractionEvent =>
            logicalOrder = interaction.order
            effectOrdinal = 0L
            updateLamportClock(interaction.lamportTick)
            if (interaction.tick > currentTick) {
              currentTick = interaction.tick
            }
            dispatchRuntimeInteraction(interaction)
          case other =>
            logWarn(s"$getEntityId: replayLoggedEvent doesn't know how to replay a ${other.getClass.getName}")
        }
      }
    catch
      case e: Throwable =>
        logError(s"Exception replaying a logged event for $getEntityId: ${e.getMessage}")
        throw e
    finally {
      isReplaying = false
      replaySendSeqs = Iterator.empty
      replayLastSendSeq = -1L
    }
  }

  /** The synchronization mode is selected for the whole simulation run. */
  private def isTimeWarp: Boolean = currentTimeManagerType == TimeManagerTypeEnum.TIME_WARP

  /** Hook called from [[rollbackAndCascade]] (Time Warp only) right after undone state is
    * restored, so a subclass can drop any actor-local, non-`state` stash whose irrevocable
    * follow-up (e.g. a dynamic-actor spawn deferred past a GVT watermark, see
    * `docs/TIME_WARP_DESIGN.md`) was tied to a tick this rollback just undid. Default: no-op —
    * only relevant to subclasses that actually defer an irrevocable step this way.
    */
  protected def onRollbackDiscardPending(targetTick: Tick): Unit = ()

  /** Legacy shortcut only. Accounted execution must restore the event's prefix before judging
    * a reply: even request identity in the speculative suffix may be undone later.
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
  private def rollbackAndCascade(
                                  targetTick: Tick,
                                  rollbackFn: () => Seq[LoggedEvent],
                                  resyncSchedule: Boolean = true
  ): Seq[LoggedEvent] = {
    val currentTickBeforeRestore = currentTick
    val undone = rollbackFn()
    undone.iterator.map(_.event).collect { case e: ActorInteractionEvent => e.messageId }
      .foreach(processedMessages.remove)
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
    // Cancellation can preserve an earlier event at the same tick, so effects follow execution
    // identity rather than tick alone. Already committed output still requires a proven GVT.
    if (isTimeWarp) {
      val firstUndoneSeq = undone.iterator.map(_.seq).min
      val (kept, discarded) = reportBuffer.partition(_.eventSeq < firstUndoneSeq)
      if (discarded.nonEmpty) {
        reportBuffer.clear()
        reportBuffer ++= kept
        discarded.foreach {
          case buffered =>
            if (buffered.event.label != null) ActorMetrics.reportDiscardedByRollback.labels(buffered.event.label).inc()
        }
      }
    }
    onRollbackDiscardPending(targetTick)
    val retracted = AntiMessageCascade.messagesToRetract(undone)
    // #timewarp-trace
    core.actor.trace.TimeWarpTrace.trace(
      getEntityId,
      currentTickBeforeRestore,
      "rollback_undone",
      s"targetTick=$targetTick undoneSeqs=${undone.map(_.seq).sorted.mkString(",")} " +
        s"undoneTicks=${undone.map(_.tick).sorted.mkString(",")} retractedMessageIds=${retracted.map(_.messageId).mkString(",")}"
    )
    retracted.foreach(sendAntiMessage)
    if (resyncSchedule) resyncScheduleAfterRollback()
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
                                       undone: Seq[LoggedEvent],
                                       annihilated: Option[MessageId]
  ): Seq[ActorInteractionEvent] =
    undone
      .sortBy(le => (le.tick, le.seq))
      .map(_.event)
      .collect { case e: ActorInteractionEvent => e }
      .filterNot(e => e.isAntiMessage || annihilated.contains(e.messageId))

  /** Replacement is one revisioned operation, never a fabricated dispatch completion. */
  private def resyncScheduleAfterRollback(): Unit = {
    if (currentTimeManager == null) return
    val next = if (usesAccountedRuntime) pendingSelf
    else if (outstandingAwaitedReplies.nonEmpty) None else pendingSelf.orElse {
      rollbackHandler.lastScheduleOutcome match {
        case Some(Some(tick)) => Some(EventOrder.root(tick, getClass.getName, getEntityId.toLong))
        case None => Some(EventOrder.root(currentTick, getClass.getName, getEntityId.toLong))
        case Some(None) => None
      }
    }
    sendTimeManager(currentTimeManager, ReplaceScheduleEvent(schedulingIdentity(), nextScheduleRevision(), next,
      currentGeneration, next.isEmpty && outstandingAwaitedReplies.nonEmpty))
  }

  /** Finds the logged event (if any) this actor recorded for processing the interaction identified
    * by `messageId` — i.e. the original send an incoming anti-message refers to. Matches on the
    * sender/tick/seq triple `ActorInteractionEvent.messageId` derives, since that's the only event
    * type [[handleInteractWith]] ever logs whose identity an anti-message could reference.
    */
  private def findLoggedEventForMessage(messageId: MessageId): Option[LoggedEvent] =
    rollbackHandler.findEvent {
      case interaction: ActorInteractionEvent => interaction.messageId == messageId
      case _                                  => false
    }

  /** Sends a real anti-message retracting `sentMessage`'s original send, to the same receiver via
    * the same routing (`sendMessageToShard`/`sendMessageToPool`) a normal send would use — but
    * carrying the *original* send's `tick`/`seq` (not this actor's current ones), so the receiver
    * can find and undo exactly that send in its own log via [[findLoggedEventForMessage]].
    */
  private def sendAntiMessage(sentMessage: SentMessage): Unit = {
    ActorMetrics.antiMessagesSent.labels(cachedActorTypeLabel).inc()
    val actorType = CreationTypeEnum.valueOf(sentMessage.receiverActorType)
    if (actorType == PoolDistributed) {
      sendMessageToPool(
        entityId = sentMessage.receiverId,
        data = AntiMessagePayload,
        eventType = "anti-message",
        tick = sentMessage.messageId.tick,
        seq = sentMessage.messageId.seq,
        isAntiMessage = true,
        receiverModel = sentMessage.receiverShardId
      )
    } else {
      sendMessageToShard(
        entityId = sentMessage.receiverId,
        shardId = sentMessage.receiverShardId,
        data = rollback.AntiMessagePayload,
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

  /** Namespaced, versioned model metadata for behavior-affecting fields outside `state`.
    * Prefer `state`; these hooks let models compose adapters without extending core schemas.
    */
  protected def captureModelSnapshot(): Map[String, String] = Map.empty

  protected def restoreModelSnapshot(metadata: Map[String, String]): Unit = ()
  
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
      logicalOrder = logicalOrder,
      terminalIntent = terminalIntent,
      runtimeBorn = runtimeBorn,
      pendingSelf = pendingSelf,
      startTick = startTick,
      lamportClock = lamportClock.getClock,
      currentTimeManagerType = currentTimeManagerType,
      dependencyIds = depIds,
      dependencyTypes = depTypes,
      dependencyResourceIds = depResourceIds,
      dependencyActorTypes = depActorTypes,
      modelSnapshot = captureModelSnapshot(),
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
    logicalOrder = snapshot.logicalOrder
    terminalIntent = snapshot.terminalIntent
    runtimeBorn = snapshot.runtimeBorn
    pendingSelf = snapshot.pendingSelf
    startTick = snapshot.startTick
    lamportClock.update(snapshot.lamportClock)
    currentTimeManagerType = snapshot.currentTimeManagerType
    restoreModelSnapshot(snapshot.modelSnapshot)

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

    relationships.clear()
    if (snapshot.dependencyIds.nonEmpty) {
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
  private case class AppliedMigration(batchId: String, token: String, revision: Long, store: ActorRef)
  private var appliedMigration: Option[AppliedMigration] = None

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    accountedCoordinator.foreach(_ ! RuntimeFailure(s"LP restart requires coordinated recovery: $getEntityId (${reason.getMessage})"))
    super.preRestart(reason, message)
  }

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
          logError(s"Entity '$entityId': migration window active but SnapshotManager is not registered")
          context.stop(self)
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
        if (usesAccountedRuntime) {
          onFinishInitialize()
          initializationAcks += creatorManager -> StartEntityAckEvent(entityId = entityId)
        } else creatorManager ! StartEntityAckEvent(entityId = entityId)
        if (state != null) {
          if (state.isSetScheduleOnTimeManager) {
            registerOnTimeManager()
          } else if (isTimeWarp || usesAccountedRuntime) {
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

  private def schedulingIdentity(): Identify =
    Identify(id = getEntityId.toLong, resourceId = properties.resourceId, classType = getClass.getName,
      actorRef = getPath, actorType = properties.actorType.toString)

  private def registerOnTimeManager(): Unit = {
    pendingSelf = Some(EventOrder.root(startTick, getClass.getName, getEntityId.toLong))
    if (isTimeWarp) {
      rollbackHandler.initialize(startTick)
    }
    if (usesAccountedRuntime) {
      accountedCoordinator.get ! JoinRuntime(schedulingIdentity(), self, pendingSelf, startTick, runtimeIncarnation)
      return
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
    if (isTimeWarp) rollbackHandler.initialize(startTick)
    if (usesAccountedRuntime) {
      accountedCoordinator.get ! JoinRuntime(schedulingIdentity(), self, None, startTick, runtimeIncarnation, runtimeBirth)
      return
    }
    val timeManager = getTimeManager(currentTimeManagerType)
    if (timeManager == null) return
    timeManager ! core.entity.event.control.execution.RegisterPassiveActorEvent(actorId = entityId.toLong, identify = Some(selfIdentify()))
  }

  override protected def onInitialize(event: InitializeEvent): Unit = {
    entityId = event.id
    accountedCoordinator = event.data.runtimeCoordinator.orElse(accountedCoordinator)
    runtimeBirth = event.data.runtimeBirth
    runtimeBorn = runtimeBirth.isEmpty
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
        if (usesAccountedRuntime) { failRuntime(e); return }
        logError(s"Failed to deserialize state for $entityId: ${e.getMessage}", e)
    }
    if (usesAccountedRuntime && state == null) {
      failRuntime(new IllegalArgumentException(s"null initialized state for $getEntityId"))
      return
    }
    if (usesAccountedRuntime) {
      try validateRuntimeCapabilities()
      catch { case scala.util.control.NonFatal(e) => failRuntime(e); return }
    }
    relationships.clear()
    relationships ++= event.data.relationships
    reporters = event.data.reporters
    // The rollback floor must include model initialization, including a seeded plan cursor.
    onFinishInitialize()
    if (usesAccountedRuntime) initializationAcks += event.actorRef -> InitializeEntityAckEvent(entityId = entityId)
    if (state != null) {
      startTick = state.getStartTick
      if (runtimeBirth.nonEmpty) {
        startTick = runtimeBirth.get.tick
        currentTick = startTick
        registerPassivelyOnTimeManager()
      } else if (state.isSetScheduleOnTimeManager) {
        try
          registerOnTimeManager()
        catch {
          case e: Exception =>
            if (usesAccountedRuntime) { failRuntime(e); return }
            logError(
              s"$entityId: registerOnTimeManager() FAILED with ${e.getClass.getName}: ${e.getMessage}",
              e
            )
        }
      } else if (isTimeWarp || usesAccountedRuntime) {
        try
          registerPassivelyOnTimeManager()
        catch {
          case e: Exception =>
            if (usesAccountedRuntime) { failRuntime(e); return }
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

    if (requiresPostLoadRegistration && runtimeBirth.isEmpty && creatorManager != null && state != null) {
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
      if (!usesAccountedRuntime) event.actorRef ! InitializeEntityAckEvent(entityId = entityId)
    catch {
      case e: Exception =>
        logError(
          s"$entityId: FAILED to send InitializeEntityAckEvent: ${e.getClass.getName}: ${e.getMessage}",
          e
        )
    }
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
    require(horizonReporting.isEmpty, "model sends during horizon reporting")
    // Replay only rebuilds `state`: the original send of a surviving event was already delivered and
    // is never retracted, so repeating it would hand the receiver a duplicate. Sends of undone
    // events are retracted by anti-message, not resent here.
    val outgoingOrder = nextEffectOrder(currentTick)
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
      currentEventSentMessages += rollback.SentMessage(
        messageId = rollback.MessageId(senderId = getEntityId, tick = currentTick, seq = messageSendSeq,
          senderType = if (usesAccountedRuntime) getClass.getName else "",
          epoch = runtimeEpoch.getOrElse(""), incarnation = if (usesAccountedRuntime) runtimeIncarnation else ""),
        receiverId = entityId,
        receiverShardId = if (shardId != null) shardId else "",
        receiverActorType = actorType.toString
      )
      messageSendSeq
    } else {
      0L
    }
    if (actorType == PoolDistributed) {
      sendMessageToPool(entityId, data, eventType, seq = seq, order = Some(outgoingOrder), receiverModel = Option(shardId).getOrElse(""))
    } else {
      sendMessageToShard(entityId, shardId, data, eventType, seq = seq, order = Some(outgoingOrder))
    }
  }

  private def submitRuntimeInteraction(target: Long, model: String, data: AnyRef, eventType: String,
    tick: Tick, seq: Long, anti: Boolean, order: Option[EventOrder]): Unit = {
    val message = ActorInteractionEvent(tick, getLamportClock, getEntityId.toLong, getShardId,
      self.path.name, getClass.getName, eventType, data, properties.actorType.toString,
      properties.resourceId, seq, anti, order, runtimeEpoch.get, runtimeIncarnation)
    val transport = if (usesConservativeRuntime || distributedRuntime) currentTimeManager else accountedCoordinator.get
    transport ! RouteInteraction(runtimeEpoch.get,
      LogicalProcessId(if (model == null || model.isEmpty) "" else StringUtil.getModelClassName(model), target), message)
  }

  private def sendMessageToShard(
    entityId: Long,
    shardId: String,
    data: AnyRef,
    eventType: String = "default",
    tick: Tick = currentTick,
    seq: Long = 0L,
    isAntiMessage: Boolean = false,
    order: Option[EventOrder] = None
  ): Unit = {
    if (usesAccountedRuntime) {
      submitRuntimeInteraction(entityId, shardId, data, eventType, tick, seq, isAntiMessage, order)
      return
    }
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
        isAntiMessage = isAntiMessage,
        order = order
      )
    )
  }

  private def sendMessageToPool(
    entityId: Long,
    data: AnyRef,
    eventType: String = "default",
    tick: Tick = currentTick,
    seq: Long = 0L,
    isAntiMessage: Boolean = false,
    order: Option[EventOrder] = None,
    receiverModel: String = ""
  ): Unit = {
    if (usesAccountedRuntime) {
      submitRuntimeInteraction(entityId, receiverModel, data, eventType, tick, seq, isAntiMessage, order)
      return
    }
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
      isAntiMessage = isAntiMessage,
      order = order
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
    if ((event.order.nonEmpty && event.scheduleRevision < scheduleRevision) ||
      (event.generation > 0L && event.generation <= currentGeneration)) {
      sendTimeManager(event.actorRef, FinishEvent(self, schedulingIdentity(), currentTick, timeManager = event.actorRef,
        generation = event.generation, discardedDispatch = true))
      return
    }
    currentTimeManager = event.actorRef
    currentGeneration = event.generation
    val undoneInteractions =
      if (isTimeWarp && state != null && (event.tick < currentTick ||
        event.order.exists(o => logicalOrder.exists(_ > o)))) {
        interactionsToReprocess(
          rollbackAndCascade(event.tick,
            () => event.order.fold(rollbackHandler.rollbackTo(event.tick))(rollbackHandler.rollbackBefore),
            resyncSchedule = false), annihilated = None)
      } else Seq.empty
    currentTick = event.tick
    logicalOrder = event.order
    effectOrdinal = 0L
    pendingSelf = None
    // §4: this SpontaneousEvent is the one channel an OptimisticLocalTimeManager already has to
    // reach this actor regularly — piggyback its last-known GVT here rather than inventing a
    // separate LTM-to-actor broadcast. An idle actor simply flushes on its next dispatch instead of
    // the instant GVT technically clears it; that's a bounded delay, not a correctness gap (nothing
    // buffered can be observed as "sent" by anyone until it actually is).
    if (isTimeWarp && !usesAccountedRuntime) event.gvt.foreach(flushBufferedReportsUpTo)
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
      currentEventSeq = processedEventSeq + 1L
      currentEventSentMessages.clear()
      currentEventReads.clear()
      currentEventScheduleOutcome = None
    }
    try
      if (isTimeWarp) EventReadJournal.withRecording(currentEventReads)(actSpontaneous(event))
      else actSpontaneous(event)
    catch
      case e: Throwable =>
        if (usesAccountedRuntime) { failRuntime(e); return }
        logError(
          s"Exception during actSpontaneous at tick=$currentTick for ${getEntityId}: ${e.getMessage}"
        )
        e.printStackTrace()
        if (state == null) onFinishSpontaneous(None)
        else onFinishSpontaneous(Some(currentTick + 1))
    if (usesAccountedRuntime && !_spontaneousFinishSent && outstandingAwaitedReplies.nonEmpty)
      deferFinishSpontaneous()
    if (!_spontaneousFinishSent) {
      if (usesAccountedRuntime) {
        failRuntime(new IllegalStateException(s"missing spontaneous completion at tick=$currentTick"))
        return
      }
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
        recordedReads = currentEventReads.toVector,
        scheduleOutcome = currentEventScheduleOutcome
      )
      currentEventSeq = 0L
    }
    undoneInteractions.foreach(inputQueue.enqueue)
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
      cancelledMessages.add(event.messageId)
      findLoggedEventForMessage(event.messageId) match {
        case Some(logged) =>
          ActorMetrics.antiMessageRollbackTriggered.labels(cachedActorTypeLabel).inc()
          val undone = rollbackAndCascade(logged.tick, () => rollbackHandler.rollbackFrom(logged.seq))
          interactionsToReprocess(undone, annihilated = Some(event.messageId)).foreach(inputQueue.enqueue)
        case None =>
          logDebug(s"$getEntityId: retaining cancellation for messageId=${event.messageId}")
      }
      return
    }

    if (isTimeWarp && (cancelledMessages.contains(event.messageId) || processedMessages.contains(event.messageId))) {
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
    var restoredPrefix = false
    if (isTimeWarp && (event.tick < currentTick || event.order.exists(o => logicalOrder.exists(_ > o)))) {
      if (!usesAccountedRuntime && isKnownSupersededReply(event)) {
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
      restoredPrefix = true
      undoneInteractions = interactionsToReprocess(
        rollbackAndCascade(event.tick,
          () => event.order.fold(rollbackHandler.rollbackAfter(event.tick))(rollbackHandler.rollbackBefore)), annihilated = None
      )
    }

    if (pendingSelf.exists(p => event.order.exists(p < _)) && restoredPrefix) {
      inputQueue.enqueue(event)
      undoneInteractions.foreach(inputQueue.enqueue)
      return
    }
    if (event.tick > currentTick) currentTick = event.tick
    logicalOrder = event.order
    effectOrdinal = 0L
    if (isTimeWarp) {
      currentEventSeq = processedEventSeq + 1L
      currentEventSentMessages.clear()
      currentEventReads.clear()
      currentEventScheduleOutcome = None
    }
    try
      if (isTimeWarp) EventReadJournal.withRecording(currentEventReads)(dispatchRuntimeInteraction(event))
      else dispatchRuntimeInteraction(event)
    catch
      case e: Exception =>
        if (usesAccountedRuntime) { failRuntime(e); return }
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
        recordedReads = currentEventReads.toVector,
        scheduleOutcome = currentEventScheduleOutcome
      )
      processedMessages.add(event.messageId)
      currentEventSeq = 0L
    }
    // After the straggler, so they run in causal order. Only the straggler branch fills this.
    undoneInteractions.foreach(inputQueue.enqueue)
  }

  /** Called when the actor receives an interaction event from another actor. Override this method
    * to handle interactions.
    * @param event
    *   The interaction event
    */
  def actInteractWith(event: ActorInteractionEvent): Unit = ()

  private def birthReplyKey(child: LogicalProcessId): String = s"runtime-birth:${child.model}:${child.id}"

  private def dispatchRuntimeInteraction(event: ActorInteractionEvent): Unit = event.data match {
    case birth: RuntimeBirthData =>
      val origin = runtimeBirth.getOrElse(throw new IllegalStateException("birth addressed an existing source LP"))
      require(!runtimeBorn && event.order.contains(origin.order) &&
        origin.parent == LogicalProcessId(event.actorClassType, event.actorRefId), "conflicting birth identity")
      state = internStateStrings(JsonUtil.convertValue[T](birth.stateJson))
      require(state != null && (!state.isSetScheduleOnTimeManager || state.getStartTick >= event.tick),
        "child schedules before its creating event")
      relationships.clear()
      relationships ++= birth.relationships
      runtimeBorn = true
      if (requiresPostLoadRegistration) handlePostLoadRegistration()
      if (state.isSetScheduleOnTimeManager) scheduleEvent(state.getStartTick)
      sendMessageTo(event.actorRefId, event.actorClassType,
        RuntimeBirthAccepted(LogicalProcessId(getClass.getName, getEntityId.toLong), event.seq),
        actorType = CreationTypeEnum.valueOf(event.actorType))
    case accepted: RuntimeBirthAccepted =>
      require(accepted.child == LogicalProcessId(event.actorClassType, event.actorRefId), "birth reply identity mismatch")
      val key = birthReplyKey(accepted.child)
      if (matchesAwaitedReply(key, "runtime-birth", accepted.requestSeq)) {
        consumeAwaitedReply(key)
        onDynamicActorInitialized(accepted.child.id.toString, accepted.child.model)
        if (pendingSelf.isEmpty && outstandingAwaitedReplies.isEmpty) onFinishSpontaneous(None)
      }
    case _ =>
      require(runtimeBorn, "model input before an active birth")
      actInteractWith(event)
  }

  private def runtimeMinimum: Option[Long] = {
    (inputQueue.minimumTick.toSeq ++ pendingSelf.map(_.tick) ++ pendingCancellations.map(_.tick) ++
      (if (distributedRuntime || usesConservativeRuntime) Iterable.empty else outstandingAwaitedReplies.values.map(_.sentAtTick)) ++ pendingCreationCallbacks.map(_._2) ++
      pendingDynamicInits.values.map(_._2.data.data).map(_ => currentTick)).minOption
  }

  // Later interactions can extend history beyond the terminal decision without scheduling
  // another wake. Their state and reports must remain rollbackable until they also commit.
  private def mayRetireTerminal(gvt: Long): Boolean =
    (terminalIntent.exists(_.tick < gvt) || (runtimeBirth.exists(_.tick < gvt) && !runtimeBorn)) &&
      currentTick < gvt && retirementFence < gvt && runtimeMinimum.isEmpty && outstandingAwaitedReplies.isEmpty

  private def retainedRuntimeState: RuntimeRetention = {
    val (checkpoints, events, reads) = rollbackHandler.retainedCounts
    RuntimeRetention(checkpoints, events, reads, processedMessages.size, cancelledMessages.size,
      reportBuffer.size, rollbackHandler.uncommittedEventCount)
  }

  private def acceptRuntimeInput(event: AnyRef): Unit = {
    val tick = event match {
      case e: SpontaneousEvent => e.tick
      case e: ActorInteractionEvent => e.tick
      case _ => throw new IllegalArgumentException(s"unsupported runtime LP input ${event.getClass.getName}")
    }
    runtimeBirth.foreach { origin =>
      val order = event match {
        case e: SpontaneousEvent => e.order
        case e: ActorInteractionEvent => e.order
      }
      require(tick >= origin.tick && order.forall(_ >= origin.order), "input precedes the child's birth order")
    }
    retirementFence = math.max(retirementFence, tick)
    event match {
    case e: SpontaneousEvent if (e.order.nonEmpty && e.scheduleRevision < scheduleRevision) ||
      (e.generation > 0L && e.generation <= currentGeneration) =>
      sendTimeManager(e.actorRef, FinishEvent(self, schedulingIdentity(), currentTick,
        timeManager = e.actorRef, generation = e.generation, discardedDispatch = true))
    case e: SpontaneousEvent => inputQueue.enqueue(e)
    case e: ActorInteractionEvent if e.isAntiMessage => pendingCancellations.enqueue(e)
    case e: ActorInteractionEvent =>
      if (e.data.isInstanceOf[RuntimeBirthData]) require(runtimeBirth.exists(origin =>
        e.order.contains(origin.order) &&
          origin.parent == LogicalProcessId(e.actorClassType, e.actorRefId)), "conflicting birth addressed an existing LP")
      inputQueue.enqueue(e)
    case other => throw new IllegalArgumentException(s"unsupported runtime LP input ${other.getClass.getName}")
    }
  }

  private def nextConservativeInput: Option[EventOrder] = inputQueue.headOption.map {
    case e: ActorInteractionEvent => e.order.getOrElse(throw new IllegalStateException("unordered conservative interaction"))
    case e: SpontaneousEvent => e.order.getOrElse(throw new IllegalStateException("unordered conservative dispatch"))
  }

  override def receive: Receive = {
    case ContinueOptimisticExecution =>
      continuationQueued = false
      try drainInputs() catch { case scala.util.control.NonFatal(e) => failRuntime(e) }
    case grant: SetOptimisticExecutionLimits =>
      require(distributedRuntime && runtimeAuthority.contains(sender()) && runtimeFrozen,
        "execution limits outside an authorized frozen phase")
      require(executionLimits.forall(previous => grant.frontier >= previous.frontier && grant.limits == previous.limits),
        "execution limits changed during a run")
      // At an input boundary the frozen frontier can be one tick beyond loaded coverage.
      val minimumCeiling = if (grant.frontier == Long.MinValue) Long.MinValue else grant.frontier - 1L
      require(grant.ceiling >= math.min(minimumCeiling, runtimeHorizon) && grant.ceiling <= runtimeHorizon,
        "execution ceiling outside the run horizon")
      executionLimits = Some(grant)
    case SetRuntimeHorizon(horizon) =>
      require(distributedRuntime && runtimeAuthority.contains(sender()) && runtimeFrozen && runtimeCommitRound == 0L,
        "execution horizon outside runtime startup")
      runtimeHorizon = horizon
    case RuntimeJoined(epoch, coordinator, owner, distributed) =>
      require(accountedCoordinator.contains(coordinator), "runtime registration changed coordinator")
      runtimeEpoch = Some(epoch)
      currentTimeManager = owner
      distributedRuntime = distributed
      if (distributed) runtimeFrozen = true
      if (usesConservativeRuntime) {
        runtimeFrozen = true
        flushBufferedReportsUpTo(Long.MaxValue)
      }
      initializationAcks.foreach { case (target, ack) => target ! ack }
      initializationAcks.clear()
      drainInputs()
    case ConservativeDelivery(epoch, token, event) =>
      try {
        require(usesConservativeRuntime && runtimeEpoch.contains(epoch) && sender() == currentTimeManager,
          "invalid conservative delivery")
        require(state != null, s"conservative input to uninitialized LP $getEntityId")
        event match {
          case e: ActorInteractionEvent => require(!e.isAntiMessage, "anti-message in conservative run")
          case _: SpontaneousEvent => ()
          case _ => throw new IllegalArgumentException("invalid conservative input")
        }
        acceptRuntimeInput(event)
        sender() ! ConservativeInputAccepted(epoch, token, nextConservativeInput)
      } catch { case scala.util.control.NonFatal(e) => failRuntime(e) }
    case ExecuteConservativeBatch(round, tick, microstep) =>
      try {
        require(usesConservativeRuntime && sender() == currentTimeManager && !runtimeFailed,
          "invalid conservative execution grant")
        while (nextConservativeInput.exists(o => o.tick == tick && o.microstep == microstep) && !runtimeFailed) {
          val order = nextConservativeInput.get
          require(logicalOrder.forall(_ < order), s"conservative causality violation: $logicalOrder then $order")
          inputQueue.dequeue() match {
            case e: ActorInteractionEvent => handleInteractWith(e)
            case e: SpontaneousEvent => handleSpontaneous(e)
          }
        }
        if (!runtimeFailed) sender() ! ConservativeBatchProcessed(round, nextConservativeInput,
          outstandingAwaitedReplies.size, terminalIntent.nonEmpty)
      } catch { case scala.util.control.NonFatal(e) => failRuntime(e) }
    case RetireConservativeProcess(round, truncated, finalHorizon) =>
      if (!usesConservativeRuntime || sender() != currentTimeManager || runtimeFailed ||
        (!truncated && (inputQueue.nonEmpty || outstandingAwaitedReplies.nonEmpty))) {
        failRuntime(new IllegalStateException(s"unsafe conservative retirement of $getEntityId"))
      } else try {
        require(finalHorizon.isEmpty || truncated, "horizon reporting without truncation")
        finalHorizon.foreach(finalizeHorizonReports)
        sender() ! ConservativeProcessRetired(round)
        physicallyRetire()
      } catch { case scala.util.control.NonFatal(e) => failRuntime(e) }
    case RuntimeDelivery(epoch, token, event) =>
      try {
        require(runtimeAuthority.contains(sender()), "runtime delivery from non-owner")
        require(runtimeEpoch.contains(epoch), "wrong runtime delivery epoch")
        val tick = event match {
          case e: SpontaneousEvent => e.tick
          case e: ActorInteractionEvent => e.tick
          case _ => throw new IllegalArgumentException("not an LP event")
        }
        require(tick >= runtimeGvt, s"LP input $tick below frontier $runtimeGvt")
        // The coordinator only registers LPs after initialization and retires them only after
        // their committed work is drained. Receiving any input with no model state therefore
        // means a late message reached a retired/recreated shard entity (or initialization was
        // lost). Acknowledge-and-discard would hide a broken optimistic lifecycle and lose work.
        require(
          state != null,
          s"runtime delivered ${event.getClass.getSimpleName} to uninitialized LP $getEntityId"
        )
        acceptRuntimeInput(event)
        sender() ! DeliveryAccepted(epoch, token)
        drainInputs()
      } catch { case scala.util.control.NonFatal(e) => failRuntime(e) }
    case FreezeRuntime(round) =>
      if (!runtimeAuthority.contains(sender())) failRuntime(new IllegalStateException("freeze from non-owner"))
      else { runtimeFrozen = true; sender() ! RuntimeFrozen(round) }
    case CollectRuntimeSnapshot(round) =>
      require(runtimeAuthority.contains(sender()) && (!distributedRuntime || runtimeFrozen),
        "snapshot outside an authorized frozen round")
      val minimum = runtimeMinimum
      val now = System.nanoTime()
      if (core.actor.trace.TimeWarpTrace.enabled && minimum.nonEmpty && runtimeGvt != Long.MinValue &&
        now - runtimeFrontierAt > 5000000000L &&
        now - runtimeDiagnosticAt > 5000000000L) {
        runtimeDiagnosticAt = now
        logWarn(s"[RUNTIME-PENDING] round=$round frontier=$runtimeGvt minimum=$minimum pendingSelf=$pendingSelf " +
          s"queued=${inputQueue.headOption} awaited=${outstandingAwaitedReplies.toMap} " +
          s"state=${scala.util.Try(JsonUtil.toJson(state)).getOrElse(String.valueOf(state)).take(6000)}")
      }
      core.actor.trace.TimeWarpTrace.trace(getEntityId, currentTick, "runtime_snapshot",
        s"round=$round minimum=$minimum pendingSelf=$pendingSelf logicalOrder=$logicalOrder " +
          s"queued=${inputQueue.headOption} awaited=${outstandingAwaitedReplies.toMap} terminal=$terminalIntent")
      sender() ! RuntimeSnapshot(round, runtimeMinimum, outstandingAwaitedReplies.size,
        pendingCreationCallbacks.size + pendingDynamicInits.size,
        queuedDispatches = if (distributedRuntime) inputQueue.queuedDispatchCount else 0,
        retention = if (distributedRuntime) Some(retainedRuntimeState) else None)
    case CommitRuntime(round, gvt, finalHorizon) =>
      if (!runtimeAuthority.contains(sender()) || !runtimeFrozen || round <= runtimeCommitRound || gvt < runtimeGvt) {
        failRuntime(new IllegalStateException(
          s"unauthorized commit for $getEntityId at round=$round while frozen=$runtimeFrozen gvt=$gvt/$runtimeGvt"
        ))
      } else try {
        runtimeCommitRound = round
        if (gvt != runtimeGvt) runtimeFrontierAt = System.nanoTime()
        runtimeGvt = gvt
        require(finalHorizon.forall(h => gvt > h && (!distributedRuntime || h == runtimeHorizon)),
          "horizon reporting outside a safe final cut")
        finalHorizon.foreach(finalizeHorizonReports)
        flushBufferedReportsUpTo(gvt)
        executionLimits.foreach { grant =>
          if (grant.limits.fossilCollection) {
            rollbackHandler.pruneBelow(gvt)
            processedMessages.filterInPlace(_.tick >= gvt)
            cancelledMessages.filterInPlace(_.tick >= gvt)
            inputQueue.compactDiscarded()
            currentEventReads.clear()
            currentEventSentMessages.clear()
          } else rollbackHandler.markCommitted(gvt)
        }
        val eligible = mayRetireTerminal(gvt)
        committedRetirement = Some(round -> eligible)
        sender() ! RuntimeCommitted(round, eligible)
      } catch { case scala.util.control.NonFatal(e) => failRuntime(e) }
    case ResumeRuntime(_) =>
      if (!runtimeAuthority.contains(sender()) || finalizedHorizon.nonEmpty)
        failRuntime(new IllegalStateException("resume from non-owner or after horizon finalization"))
      else {
        runtimeFrozen = false
        try drainInputs() catch { case scala.util.control.NonFatal(e) => failRuntime(e) }
      }
    case RetireRuntimeProcess(round, finalCut) =>
      val coordinatorSent = runtimeAuthority.contains(sender())
      val committedThisRound = committedRetirement.exists(_._1 == round)
      val eligibleThisRound = committedRetirement.contains(round -> true)
      val terminalStillSafe = mayRetireTerminal(runtimeGvt)
      if (!coordinatorSent || !committedThisRound || (!finalCut && (!eligibleThisRound || !terminalStillSafe))) {
        failRuntime(new IllegalStateException(
          s"unauthorized or unsafe retirement for $getEntityId at round=$round finalCut=$finalCut gvt=$runtimeGvt"
        ))
      } else {
        committedRetirement = None
        sender() ! RuntimeProcessRetired(round)
        physicallyRetire()
      }
    case event: MigrationPreparationFailedEvent if awaitingMigration =>
      logError(s"Cannot restore migration '${event.batchId}': ${event.reason}")
      context.stop(self)

    case event: MigrationContextEvent if awaitingMigration =>
      if (handleMigrationContext(event)) {
        awaitingMigration = false
        unstashAll()
      }

    case _: NoPendingMigrationEvent if awaitingMigration =>
      awaitingMigration = false
      unstashAll()
      proceedNormalInit()

    case event: MigrationRestoreAlreadyAcceptedEvent =>
      val applied = appliedMigration.exists(a => a.batchId == event.batchId && a.token == event.restoreToken &&
        a.revision == event.snapshotRevision && a.store == sender())
      if (!applied) {
        sender() ! MigrationRestoreFailedEvent(entityId, event.batchId, event.restoreToken,
          event.snapshotRevision, "completed restore has no matching application in this actor; independent recovery is unsupported")
        context.stop(self)
      }

    case _ if awaitingMigration =>
      stash()

    case event: MigrationContextEvent      => handleMigrationContext(event)
    case _: ActorInteractionEvent | _: SpontaneousEvent if usesAccountedRuntime =>
      failRuntime(new IllegalStateException("unaccounted model input; register an input producer"))
    case event: SpontaneousEvent if (event.order.nonEmpty && event.scheduleRevision < scheduleRevision) ||
      (event.generation > 0L && event.generation <= currentGeneration) =>
      sendTimeManager(event.actorRef, FinishEvent(self, schedulingIdentity(), currentTick, timeManager = event.actorRef,
        generation = event.generation, discardedDispatch = true))
      drainInputs()
    case event: SpontaneousEvent           => enqueueInput(event)
    case event: ActorInteractionEvent if event.isAntiMessage =>
      handleInteractWith(event)
      drainInputs()
    case event: ActorInteractionEvent      => enqueueInput(event)
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
    require(horizonReporting.isEmpty, "model birth during horizon reporting")
    if (usesAccountedRuntime) {
      require(logicalOrder.nonEmpty && runtimeEpoch.nonEmpty, "birth outside an executing model event")
      val model = StringUtil.getModelClassName(classType)
      val child = LogicalProcessId(model, entityId)
      val json = stateData match { case value: String => value; case value => JsonUtil.toJson(value) }
      val selectedManager = Option(timeManagers).flatMap(_.get(currentTimeManagerType)).getOrElse(currentTimeManager)
      sendMessageTo(entityId, model, RuntimeBirthData(json, relationships.toMap, properties.resourceId,
        Map(currentTimeManagerType -> selectedManager),
        Option(reporters).fold(Map.empty[ReportTypeEnum, ActorRef])(_.toMap)), eventType = "runtime_birth")
      registerAwaitedReply(birthReplyKey(child), "runtime-birth")
      return
    }
    if (isReplaying) return
    accountedCoordinator.foreach(_ ! BeginRuntimeCreation(entityId.toString, currentTick))
    val initEvent = InitializeEvent(
      id = entityId.toString,
      actorRef = self,
      data = InitializeData(
        data = stateData,
        resourceId = properties.resourceId,
        timeManagers = timeManagers,
        runtimeCoordinator = accountedCoordinator,
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
      reporters = reporters,
      runtimeCoordinator = accountedCoordinator
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
    if (usesAccountedRuntime) {
      accountedCoordinator.foreach(_ ! EndRuntimeCreation(event.entityId))
      pendingCreationCallbacks.enqueue(event.entityId -> currentTick)
      drainInputs()
    } else {
      val classType = dynamicActorClassTypes.remove(event.entityId).getOrElse("")
      onDynamicActorInitialized(event.entityId, classType)
    }
  }

  override protected def acceptPhysicalDestruction(event: DestructEvent): Boolean = {
    if (usesAccountedRuntime) {
      accountedCoordinator.foreach(_ ! RuntimeFailure(s"uncommitted destruction of $getEntityId"))
      false
    } else true
  }

  /** A terminal decision is speculative until a proven frontier passes its producing history. */
  override protected def selfDestruct(): Unit = {
    require(horizonReporting.isEmpty, "model destruction during horizon reporting")
    if (isTimeWarp || usesAccountedRuntime) {
      terminalIntent = Some(logicalOrder.getOrElse(EventOrder.root(currentTick, getClass.getName, getEntityId.toLong)))
      onFinishSpontaneous(None)
    } else physicallyRetire()
  }

  private def physicallyRetire(): Unit =
    if (properties != null && properties.actorType == LoadBalancedDistributed)
      context.parent ! ShardRegion.Passivate(PoisonPill)
    else context.stop(self)

  override protected def onDestruct(event: DestructEvent): Unit = {
    if (usesAccountedRuntime) {
      accountedCoordinator.foreach(_ ! RuntimeFailure(s"uncommitted destruction of $getEntityId"))
    } else {
      if (isTimeWarp) flushBufferedReportsUpTo(Long.MaxValue)
      super.onDestruct(event)
    }
  }

  private def handleMigrationContext(event: MigrationContextEvent): Boolean = {
    try {
      require(event.snapshotManagerRef != null && event.restoreToken.nonEmpty &&
        event.batchId.nonEmpty && event.snapshotRevision > 0L, "missing restore confirmation identity")
      require(event.snapshot != null && event.snapshot.entityId == entityId, "restore entity mismatch")
      val identity = AppliedMigration(event.batchId, event.restoreToken, event.snapshotRevision, event.snapshotManagerRef)
      appliedMigration match {
        case Some(existing) => require(existing == identity, "a different restore was already applied to this actor")
        case None =>
          applyMigrationSnapshot(event.snapshot)
          if (event.timeManagers.nonEmpty) timeManagers = event.timeManagers
          if (event.reporters.nonEmpty) reporters = event.reporters
          creatorManager = null
          if (shouldRegisterOnTimeManagerAfterMigration()) registerOnTimeManager()
          onFinishInitialize()
          onMigrationRestore()
          appliedMigration = Some(identity)
      }
      event.snapshotManagerRef ! MigrationRestoredAckEvent(entityId, event.batchId,
        event.restoreToken, event.snapshotRevision)
      true
    } catch {
      case scala.util.control.NonFatal(error) =>
        val reason = Option(error.getMessage).getOrElse(error.getClass.getName)
        logError(s"Migration restore '${event.batchId}' failed: $reason", error)
        Option(event.snapshotManagerRef).foreach(_ ! MigrationRestoreFailedEvent(entityId,
          event.batchId, event.restoreToken, event.snapshotRevision, reason))
        Option(event.lbmRef).foreach(_ ! MigrationPreparationFailedEvent(event.batchId, entityId, reason))
        context.stop(self)
        false
    }
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
    require(horizonReporting.isEmpty, "model completion during horizon reporting")
    // Time Warp replay (see isReplaying's doc): actSpontaneous calling this during
    // replayLoggedEvent must not re-signal the time manager -- that signal already happened, for
    // real, the first time this event was live-dispatched. Nothing to update here either:
    // _spontaneousFinishSent's safety-net check only runs inside handleSpontaneous, which replay
    // never goes through.
    if (scheduleTick.nonEmpty) terminalIntent = None
    pendingSelf = scheduleTick.map(nextEffectOrder)
    if (destruct && (isTimeWarp || usesAccountedRuntime)) terminalIntent = Some(logicalOrder.getOrElse(EventOrder.root(currentTick, getClass.getName, getEntityId.toLong)))
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
    sendTimeManager(currentTimeManager, FinishEvent(
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
      destruct = destruct && !usesAccountedRuntime,
      generation = currentGeneration,
      awaitingReply = scheduleTick.isEmpty && outstandingAwaitedReplies.nonEmpty,
      scheduleRevision = nextScheduleRevision(),
      nextOrder = pendingSelf
    ))
  }

  /** Sends a spontaneous event to itself. */
  protected def selfSpontaneous(): Unit = scheduleEvent(currentTick)

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
    require(horizonReporting.isEmpty, "model scheduling during horizon reporting")
    // Time Warp replay: same reasoning as onFinishSpontaneous's isReplaying guard -- this actor's
    // real future schedule was already correctly registered with the time manager the first time
    // this event was live-dispatched; replaying it must not re-register (or double-register) it.
    terminalIntent = None
    pendingSelf = Some(nextEffectOrder(tick))
    if (isReplaying) return
    if (isTimeWarp && !resyncAfterRollback) currentEventScheduleOutcome = Some(Some(tick))
    val tm =
      if (currentTimeManager != null) currentTimeManager else getTimeManager(currentTimeManagerType)
    sendTimeManager(tm, ScheduleEvent(
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
      resyncAfterRollback = resyncAfterRollback,
      revision = nextScheduleRevision(),
      order = pendingSelf.map(o => org.htc.protobuf.core.entity.event.communication.LogicalEventOrder(o.tick, o.microstep, o.key))
    ))
  }

  /** Only reporting is allowed: the globally closed final cut forbids new model effects. */
  protected def onHorizonFinalization(horizon: Tick): Unit = ()

  private def finalizeHorizonReports(horizon: Tick): Unit = {
    require(finalizedHorizon.isEmpty && currentTick <= horizon && runtimeMinimum.forall(_ > horizon),
      "horizon reporting before all in-horizon work is resolved or after finalization")
    val previousTick = currentTick
    horizonReporting = Some(horizon)
    currentTick = horizon
    try {
      if (runtimeBorn) onHorizonFinalization(horizon)
      finalizedHorizon = Some(horizon)
    } finally {
      currentTick = previousTick
      horizonReporting = None
    }
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
    // The surviving event's original report is still buffered; replaying it would emit it twice.
    if (isReplaying) return
    if (reporters.isEmpty) return
    if (event.label != null) {
      ActorMetrics.eventsProcessed.labels(event.label).inc()
    }
    val stateReporter = state.getReporterType
    val reportType = if (stateReporter != null) stateReporter else cachedDefaultReportType
    val target = if (reporters.contains(reportType)) reporters(reportType) else reporters(cachedDefaultReportType)
    reportToTarget(event, target)
  }

  private def reportToTarget(event: ReportEvent, target: ActorRef): Unit = {
    require(horizonReporting.forall(_ == event.tick), "horizon report has a different timestamp")
    if (isReplaying) return
    if (usesConservativeRuntime && runtimeEpoch.nonEmpty) currentTimeManager ! PublishConservativeReport(target, event)
    else if (isTimeWarp || usesConservativeRuntime) reportBuffer += BufferedReport(event, target, currentEventSeq)
    else {
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
      val (ready, pending) = reportBuffer.partition(_.event.tick < gvt)
      if (ready.nonEmpty) {
        reportBuffer.clear()
        reportBuffer ++= pending
        ready.foreach {
          case buffered =>
            if (buffered.event.label != null) ActorMetrics.reportFlushed.labels(buffered.event.label).inc()
            accountedCoordinator match {
              case Some(_) if usesConservativeRuntime => currentTimeManager ! PublishConservativeReport(buffered.target, buffered.event)
              case Some(_) if distributedRuntime => currentTimeManager ! PublishCommittedReport(buffered.target, buffered.event)
              case Some(coordinator) => coordinator ! PublishCommittedReport(buffered.target, buffered.event)
              case None => buffered.target ! buffered.event
            }
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
        reportToTarget(ReportEvent(
          entityId = entityId,
          tick = currentTick,
          lamportTick = getLamportClock,
          data = data,
          label = label
        ), reporter)
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
