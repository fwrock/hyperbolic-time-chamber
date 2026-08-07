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
import org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent
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

  protected var currentTimeManagerType: String =
    if (properties != null) properties.defaultTimeManagerType
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
    replayEventFn = event => replayLoggedEvent(event)
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
    * common single-rollback path this step's tests exercise is unaffected.
    */
  private def replayLoggedEvent(event: AnyRef): Unit = {
    isReplaying = true
    try
      event match {
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
    catch
      case e: Throwable =>
        logError(s"Exception replaying a logged event for $getEntityId: ${e.getMessage}")
        e.printStackTrace()
    finally isReplaying = false
  }

  /** True only under Time Warp — the one gate every new-but-not-yet-safe-to-activate call site
    * below checks before touching [[rollbackHandler]], so conservative mode is provably unaffected.
    */
  private def isTimeWarp: Boolean = currentTimeManagerType == TimeManagerTypeEnum.TIME_WARP

  /** The straggler trigger (`docs/TIME_WARP_DESIGN.md` §10): rolls this actor back to before
    * `targetTick` and sends a real anti-message for every send that gets undone in the process.
    * Called from [[handleInteractWith]] for both cases that need it — a genuinely causally-earlier
    * interaction arriving after this actor already ran ahead of it, and an incoming anti-message
    * itself (whose target tick is looked up via [[findLoggedEventForMessage]]) — since both reduce
    * to the same "undo back to a tick, cascade what that undoes" operation
    * `RollbackHistoryHandler.rollbackTo` already implements.
    */
  private def rollbackAndCascade(targetTick: Tick): Unit = {
    val undone = rollbackHandler.rollbackTo(targetTick)
    core.actor.rollback.AntiMessageCascade.messagesToRetract(undone).foreach(sendAntiMessage)
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
            entityId    = StringPool.intern(v.entityId),
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
      case (k, r) => k -> r.entityId
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
      dependencyActorTypes = depActorTypes
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

    if (snapshot.dependencyIds.nonEmpty) {
      relationships.clear()
      snapshot.dependencyIds.foreach {
        case (key, id) =>
          val classType = snapshot.dependencyTypes.getOrElse(key, "")
          val shardBucket = snapshot.dependencyResourceIds.getOrElse(key, "")
          relationships.put(
            key,
            ShardActorId(entityId = id, classType = classType, shardBucket = shardBucket)
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
        if (state != null && state.isSetScheduleOnTimeManager) {
          registerOnTimeManager()
        }
      } catch {
        case e: Exception =>
          logError(s"Error on start actor $entityId: ${e.getMessage}", e)
          e.printStackTrace()
      }
    }
    onStart()
  }

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
    if (properties.actorType == LoadBalancedDistributed) {
      timeManager ! RegisterActorEvent(
        startTick = startTick,
        actorId = entityId,
        identify = Some(
          Identify(
            id = IdUtil.format(entityId),
            resourceId = IdUtil.format(properties.resourceId),
            classType = getClass.getName,
            actorRef = getSelfShard.path.toString,
            actorType = properties.actorType.toString
          )
        )
      )
    } else {
      timeManager ! RegisterActorEvent(
        startTick = startTick,
        actorId = entityId,
        identify = Some(
          Identify(
            id = IdUtil.format(entityId),
            resourceId = IdUtil.format(properties.resourceId),
            classType = getClass.getName,
            actorRef = self.path.toString,
            actorType = properties.actorType.toString
          )
        )
      )
    }
  }

  override protected def onInitialize(event: InitializeEvent): Unit = {
    entityId = event.id
    if (event.data.timeManagers != null) {
      timeManagers = event.data.timeManagers
      currentTimeManagerType = TimeManagerTypeEnum.DISCRETE_EVENT
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
    entityId: String,
    shardId: String = null,
    data: AnyRef,
    eventType: String = "default",
    actorType: CreationTypeEnum = LoadBalancedDistributed
  ): Unit = {
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
    entityId: String,
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
        actorRefId = IdUtil.format(getEntityId),
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
    entityId: String,
    data: AnyRef,
    eventType: String = "default",
    tick: Tick = currentTick,
    seq: Long = 0L,
    isAntiMessage: Boolean = false
  ): Unit = {
    val pool = getActorPoolRef(entityId)
    pool ! ActorInteractionEvent(
      tick = tick,
      lamportTick = getLamportClock,
      actorRefId = IdUtil.format(getEntityId),
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
    if (isTimeWarp) currentEventSentMessages.clear()
    try actSpontaneous(event)
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
      rollbackHandler.recordProcessedEvent(event, tick = currentTick, seq = processedEventSeq, sentMessages = currentEventSentMessages.toSeq)
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
        case Some(logged) => rollbackAndCascade(logged.tick)
        case None =>
          logWarn(s"$getEntityId: anti-message for unknown/already-pruned messageId=${event.messageId}")
      }
      return
    }

    // Time Warp straggler detection: a real causality violation -- this interaction's tick is
    // behind what this actor already processed, meaning it ran ahead of where it should have.
    // Roll back to before it, cascading anti-messages for whatever that undoes, THEN process this
    // event normally below (now in its causally-correct position) -- Jefferson's "roll back to
    // before the violation, then process it in order," not a special second code path.
    if (isTimeWarp && event.tick < currentTick) {
      rollbackAndCascade(event.tick)
    }

    if (event.tick > currentTick) {
      currentTick = event.tick
    }
    if (isTimeWarp) currentEventSentMessages.clear()
    try actInteractWith(event)
    catch
      case e: Exception =>
        logError(
          s"Exception during actInteractWith at tick=$currentTick for ${getEntityId} " +
            s"from ${event.actorRefId} (${event.eventType}): ${e.getMessage}"
        )
        e.printStackTrace()
    if (isTimeWarp) {
      processedEventSeq += 1
      rollbackHandler.recordProcessedEvent(event, tick = currentTick, seq = processedEventSeq, sentMessages = currentEventSentMessages.toSeq)
    }
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
    entityId: String,
    stateData: Any,
    relationships: mutable.Map[String, ShardActorId] = mutable.Map.empty
  ): Unit = {
    val initEvent = InitializeEvent(
      id = entityId,
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
      entityId = entityId,
      resourceId = properties.resourceId,
      timeManagers = timeManagers,
      creatorManager = creatorManager,
      reporters = reporters
    )
    pendingDynamicInits.put(entityId, (shardRegion, initEvent))
    dynamicActorClassTypes.put(entityId, classType)
    shardRegion ! ShardRegion.StartEntity(entityId)
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
    */
  override protected def selfDestruct(): Unit =
    if (properties != null && properties.actorType == LoadBalancedDistributed) {
      context.parent ! ShardRegion.Passivate(PoisonPill)
    } else {
      context.stop(self)
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
    currentTimeManager ! FinishEvent(
      end = currentTick,
      actorRef = self,
      identify = Identify(
        id = IdUtil.format(getEntityId),
        resourceId = IdUtil.format(properties.resourceId),
        classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
        actorRef = getPath,
        actorType = properties.actorType.toString
      ),
      scheduleTick = scheduleTick.map(_.toString),
      scheduleEvent = None,
      timeManager = currentTimeManager,
      destruct = destruct,
      generation = currentGeneration
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
  protected def scheduleEvent(tick: Tick): Unit = {
    // Time Warp replay: same reasoning as onFinishSpontaneous's isReplaying guard -- this actor's
    // real future schedule was already correctly registered with the time manager the first time
    // this event was live-dispatched; replaying it must not re-register (or double-register) it.
    if (isReplaying) return
    val tm =
      if (currentTimeManager != null) currentTimeManager else getTimeManager(currentTimeManagerType)
    tm ! ScheduleEvent(
      tick = tick,
      actorRef = getPath,
      identify = Some(
        Identify(
          id = getEntityId,
          resourceId = IdUtil.format(properties.resourceId),
          classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
          actorRef = getPath,
          actorType = properties.actorType.toString
        )
      )
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

  /** Reports an event to the reporting system.
    * @param event
    *   The report event
    */
  protected def report(event: ReportEvent): Unit = {
    if (reporters.isEmpty) return
    if (event.label != null) {
      ActorMetrics.eventsProcessed.labels(event.label).inc()
    }
    val stateReporter = state.getReporterType
    val reportType = if (stateReporter != null) stateReporter else cachedDefaultReportType
    if (reporters.contains(reportType)) {
      reporters(reportType) ! event
    } else {
      reporters(cachedDefaultReportType) ! event
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
