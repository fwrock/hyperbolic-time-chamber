package org.interscity.htc
package core.actor

import org.apache.pekko.actor.ActorRef
import core.actor.manager.loadbalance.migration.{ MigrationSnapshot, MigrationStateStoreRegistry }
import core.entity.event.{ ActorInteractionEvent, FinishEvent, SpontaneousEvent }
import core.entity.event.control.migration.{
  MigrationContextEvent,
  MigrationRestoredAckEvent,
  NoPendingMigrationEvent,
  QueryMigrationEvent
}
import core.types.Tick
import core.entity.state.BaseState
import core.entity.control.LamportClock
import core.util.{ IdUtil, JsonUtil, StringUtil }

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.ShardActorId
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent
import org.htc.protobuf.core.entity.event.control.load.{ InitializeEntityAckEvent, StartEntityAckEvent }
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.{ InitializeEvent, NeedsPostLoadRegistrationEvent, PostLoadRegistrationAckEvent, PostLoadRegistrationEvent }
import org.interscity.htc.core.entity.event.control.report.ReportEvent
import org.interscity.htc.core.enumeration.{ ReportTypeEnum, TimeManagerTypeEnum }
import org.interscity.htc.core.metrics.MetricsServer
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.{ LoadBalancedDistributed, PoolDistributed }

import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized

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
  private val properties: Properties
)(implicit m: Manifest[T])
    extends BaseActor[T](properties) {

  // Simulation-specific fields
  protected var startTick: Tick = MinValue
  private val lamportClock = new LamportClock()
  protected var currentTick: Tick = 0

  protected val relationships: mutable.Map[String, ShardActorId] =
    if (properties != null) properties.relationships else mutable.Map[String, ShardActorId]()

  /** Backward-compat alias for [[relationships]].
    * @deprecated Use relationships instead.
    */
  @deprecated("Use relationships instead", "2.8.0")
  protected def dependencies: mutable.Map[String, ShardActorId] = relationships

  protected var reporters: mutable.Map[ReportTypeEnum, ActorRef] =
    if (properties != null) properties.reporters else null

  // Suporte para múltiplos time managers
  protected var timeManagers: mutable.Map[String, ActorRef] =
    if (properties != null && properties.timeManagers != null) properties.timeManagers
    else mutable.Map[String, ActorRef]()

  // Tipo de time manager atualmente em uso (padrão: discrete-event)
  protected var currentTimeManagerType: String =
    if (properties != null) properties.defaultTimeManagerType
    else TimeManagerTypeEnum.DISCRETE_EVENT

  protected var creatorManager: ActorRef =
    if (properties != null) properties.creatorManager else null
  private var currentTimeManager: ActorRef = uninitialized

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
      // Unregister from current time manager if needed
      // (implementation depends on requirements)

      // Switch to new time manager
      currentTimeManagerType = newManagerType

      // Register with new time manager
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

  // ── Migration State Preservation ──────────────────────────────────────────

  /** Builds a migration snapshot that includes simulation-specific metadata
    * in addition to the base actor state.
    *
    * Captured fields:
    *   - Domain state (via super)
    *   - currentTick, startTick, lamportClock (time/ordering)
    *   - currentTimeManagerType (which time manager was being used)
    *   - dependencies (from_node, to_node, etc. — needed for routing lookups)
    *
    * reporters/timeManagers/creatorManager are NOT serialized here: they are
    * injected via Properties at actor construction time on the target node (cluster-transparent).
    */
  override protected def buildMigrationSnapshot(): MigrationSnapshot = {
    val base = super.buildMigrationSnapshot()

    // Convert relationships to simple string maps for safe serialization
    val depIds = relationships.map { case (k, r) => k -> r.entityId }.toMap
    val depTypes = relationships.map { case (k, r) => k -> r.classType }.toMap
    val depResourceIds = relationships.map { case (k, r) => k -> r.shardBucket }.toMap
    val depActorTypes = relationships.map { case (k, _) => k -> "" }.toMap

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
    * reporters/timeManagers/creatorManager are already available via Properties
    * (injected at construction time on the target node). They are cluster-transparent
    * ActorRefs and do not need to be serialized across migration.
    */
  override protected def applyMigrationSnapshot(snapshot: MigrationSnapshot): Unit = {
    // Restore the base state first
    super.applyMigrationSnapshot(snapshot)

    // Restore simulation metadata
    currentTick = snapshot.currentTick
    startTick = snapshot.startTick
    lamportClock.update(snapshot.lamportClock)
    currentTimeManagerType = snapshot.currentTimeManagerType

    // Rebuild relationships from stored string maps
    if (snapshot.dependencyIds.nonEmpty) {
      relationships.clear()
      snapshot.dependencyIds.foreach { case (key, id) =>
        val classType = snapshot.dependencyTypes.getOrElse(key, "")
        val shardBucket = snapshot.dependencyResourceIds.getOrElse(key, "")
        relationships.put(key, ShardActorId(entityId = id, classType = classType, shardBucket = shardBucket))
      }
    }
  }

  // ── Migration window awaiting state ───────────────────────────────────────

  /** True while the actor has queried SM for its migration snapshot and is awaiting reply.
    * All incoming messages are stashed until MigrationContextEvent or NoPendingMigrationEvent
    * arrives.
    */
  private var awaitingMigration: Boolean = false

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    super.preStart()

    // For cluster-sharded entities (LoadBalancedDistributed), Props carry the shard region's
    // initiator ID — not this actor's actual entity ID. The correct ID is the actor's path name
    // (= the shard routing key used in EntityEnvelopeEvent, which equals IdUtil.format(rawId)).
    if (properties != null && properties.actorType == LoadBalancedDistributed) {
      entityId = self.path.name
    }

    // Migration window check: if the distributed flag is active, this entity might have been
    // migrated from another node. Query the SnapshotManager to find out.
    if (MigrationStateStoreRegistry.isMigrationActive.get()) {
      creatorManager = null // Safety: no creator manager during migration window
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
    val timeManager = getTimeManager(currentTimeManagerType)
    if (timeManager == null) {
      logWarn(s"TimeManager is NULL for $entityId (type=$currentTimeManagerType). timeManagers map: size=${if (timeManagers == null) "NULL MAP" else timeManagers.size.toString} keys=${if (timeManagers == null) "" else timeManagers.keys.mkString(",")}")
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
    // Configura time managers
    if (event.data.timeManagers != null) {
      timeManagers = event.data.timeManagers
      // Usa discrete-event como padrão
      currentTimeManagerType = TimeManagerTypeEnum.DISCRETE_EVENT
    } else {
      logWarn(s"onInitialize: timeManagers is NULL for $entityId (class=${getClass.getSimpleName})")
    }
    creatorManager = event.data.creatorManager
    try {
      state = JsonUtil.convertValue[T](event.data.data)
    } catch {
      case e: Exception =>
        logError(s"Failed to deserialize state for $entityId: ${e.getMessage}", e)
    }
    relationships.clear()
    relationships ++= event.data.relationships
    reporters = event.data.reporters
    if (state != null) {
      startTick = state.getStartTick
      if (state.isSetScheduleOnTimeManager) {
        try {
          registerOnTimeManager()
        } catch {
          case e: Exception =>
            logError(s"$entityId: registerOnTimeManager() FAILED with ${e.getClass.getName}: ${e.getMessage}", e)
        }
      }
    } else {
      logWarn(s"$entityId: state is NULL after deserialization (class=${getClass.getSimpleName}). Will still send ACK.")
    }
    // Send NeedsPostLoadRegistrationEvent BEFORE InitializeEntityAckEvent so the creator
    // processes the registration signal before it processes the ACK that may complete the batch.
    // Both messages go to the same actor (creatorManager == event.actorRef), so Pekko's
    // FIFO ordering guarantee ensures the creator sees them in this order.
    // Guard: only opt-in when state was successfully deserialized — if state is null,
    // handlePostLoadRegistration() would NPE, the coordinator ACK would never arrive,
    // and the simulation would deadlock.
    if (requiresPostLoadRegistration && creatorManager != null && state != null) {
      creatorManager ! NeedsPostLoadRegistrationEvent(
        entityId = entityId,
        classType = getClass.getName
      )
    } else if (requiresPostLoadRegistration && state == null) {
      logWarn(s"$entityId: skipping NeedsPostLoadRegistrationEvent — state is NULL (class=${getClass.getSimpleName})")
    }
    // Always send ack to the creator that initialization finished (even on state deserialization
    // failure) so that the loading pipeline does not deadlock waiting for this ack.
    try
      // InitializeEvent.actorRef is the creator/loader that requested initialization
      event.actorRef ! InitializeEntityAckEvent(entityId = entityId)
    catch {
      case e: Exception =>
        logError(s"$entityId: FAILED to send InitializeEntityAckEvent: ${e.getClass.getName}: ${e.getMessage}", e)
    }
  }

  /** Return true to opt in to the post-load registration phase.
    * The actor will receive PostLoadRegistrationEvent after all EAGER loading is complete,
    * at which point handlePostLoadRegistration() will be called. The ACK is sent automatically.
    */
  protected def requiresPostLoadRegistration: Boolean = false

  /** Override this (together with requiresPostLoadRegistration = true) to perform any
    * cross-actor registration needed before the simulation starts (e.g. BusStop → Node).
    * The PostLoadRegistrationAckEvent is sent back to the coordinator automatically after
    * this method returns.
    */
  protected def handlePostLoadRegistration(): Unit = {}

  /** Dispatched by BaseActor when PostLoadRegistrationEvent arrives.
    * Calls handlePostLoadRegistration() then sends the ACK back to the coordinator.
    * Subclasses should NOT override this — override handlePostLoadRegistration() instead.
    * ACK is always sent even if handlePostLoadRegistration() throws, so the coordinator
    * never deadlocks waiting for a missing reply.
    */
  override protected final def onPostLoadRegistration(event: PostLoadRegistrationEvent): Unit = {
    try {
      handlePostLoadRegistration()
    } catch {
      case e: Exception =>
        logError(s"$entityId: handlePostLoadRegistration() threw ${e.getClass.getSimpleName}: ${e.getMessage} — sending ACK anyway", e)
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
    if (actorType == PoolDistributed) {
      sendMessageToPool(entityId, data, eventType)
    } else {
      sendMessageToShard(entityId, shardId, data, eventType)
    }
  }

  private def sendMessageToShard(
    entityId: String,
    shardId: String,
    data: AnyRef,
    eventType: String = "default"
  ): Unit = {
    val shardingRegion = getShardRef(IdUtil.format(StringUtil.getModelClassName(shardId)))

    shardingRegion ! core.entity.event.EntityEnvelopeEvent(
      IdUtil.format(entityId),
      ActorInteractionEvent(
        tick = currentTick,
        lamportTick = getLamportClock,
        actorRefId = IdUtil.format(getEntityId),
        shardRefId = IdUtil.format(getShardId),
        actorClassType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
        actorPathRef = self.path.name,
        data = data,
        eventType = eventType,
        actorType = properties.actorType.toString,
        resourceId = properties.resourceId
      )
    )
  }

  private def sendMessageToPool(
    entityId: String,
    data: AnyRef,
    eventType: String = "default"
  ): Unit = {
    val pool = getActorPoolRef(entityId)
    pool ! ActorInteractionEvent(
      tick = currentTick,
      lamportTick = getLamportClock,
      actorRefId = IdUtil.format(getEntityId),
      shardRefId = IdUtil.format(getShardId),
      actorClassType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
      actorPathRef = self.path.name,
      data = data,
      eventType = eventType,
      actorType = properties.actorType.toString,
      resourceId = properties.resourceId
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
    if (state == null) {
      // Shard-initiator probe entities or actors whose shard migrated before re-initialization.
      // Unschedule silently — not a real simulation actor.
      if (!getEntityId.endsWith("-shard-initiator")) {
        logDebug(
          s"handleSpontaneous called with null state at tick=$currentTick for ${getEntityId} — unscheduling"
        )
      }
      onFinishSpontaneous(None)
      return
    }
    try actSpontaneous(event)
    catch
      case e: Exception =>
        logError(
          s"Exception during actSpontaneous at tick=$currentTick for ${getEntityId}: ${e.getMessage}"
        )
        e.printStackTrace()
        // Unschedule if state is null (e.g. after shard restart); otherwise retry
        if (state == null) onFinishSpontaneous(None)
        else onFinishSpontaneous(Some(currentTick + 1))
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
    MetricsServer.eventsProcessed.labels("interaction").inc()
    updateLamportClock(event.lamportTick)
    // Keep currentTick monotonically advancing: interaction events carry the sender's
    // currentTick, which may be newer than ours. Without this update, actors that
    // unregister from the TimeManager (e.g., MICRO-mode vehicles driven by Link events)
    // retain a stale currentTick. When they later call onFinishSpontaneous(Some(currentTick + 1)),
    // they schedule for an already-processed tick, and the TimeManager never dispatches them again.
    if (event.tick > currentTick) {
      currentTick = event.tick
    }
    try actInteractWith(event)
    catch
      case e: Exception =>
        logError(
          s"Exception during actInteractWith at tick=$currentTick for ${getEntityId} " +
            s"from ${event.actorRefId} (${event.eventType}): ${e.getMessage}"
        )
        e.printStackTrace()
    // save(event) // Event persistence disabled
  }

  /** Called when the actor receives an interaction event from another actor. Override this method
    * to handle interactions.
    * @param event
    *   The interaction event
    */
  def actInteractWith(event: ActorInteractionEvent): Unit = ()

  override def receive: Receive = {
    // ── Migration window: stash all until SM replies ─────────────────────────
    case event: MigrationContextEvent if awaitingMigration =>
      awaitingMigration = false
      handleMigrationContext(event)
      unstashAll()

    case _: NoPendingMigrationEvent if awaitingMigration =>
      // Not a migrated entity — proceed with normal init, then process stashed messages
      awaitingMigration = false
      unstashAll()
      proceedNormalInit()

    case _ if awaitingMigration =>
      stash()

    // ── Normal message routing ────────────────────────────────────────────────
    case event: MigrationContextEvent => handleMigrationContext(event)
    case event: SpontaneousEvent      => handleSpontaneous(event)
    case event: ActorInteractionEvent => handleInteractWith(event)
    case event                        => super.receive(event)
  }

  private def handleMigrationContext(event: MigrationContextEvent): Unit = {
    logInfo(
      s"Entity '$entityId' received MigrationContextEvent: " +
        s"timeManagers=${event.timeManagers.keys.mkString(",")}, " +
        s"reporters=${event.reporters.size}, batchId=${event.batchId}"
    )
    // Restore domain state and simulation metadata from snapshot
    applyMigrationSnapshot(event.snapshot)

    // Restore the raw entity ID stored in the snapshot (entityId may currently be path.name)
    if (event.snapshot.entityId.nonEmpty) {
      entityId = event.snapshot.entityId
    }

    // Inject simulation context (timeManagers, reporters) from SnapshotManager
    if (event.timeManagers.nonEmpty) timeManagers = event.timeManagers
    if (event.reporters.nonEmpty)    reporters    = event.reporters
    creatorManager = null

    // Register with TimeManager now that state and context are fully available
    if (state != null && state.isSetScheduleOnTimeManager) {
      registerOnTimeManager()
    }
    onFinishInitialize()

    // ACK to LoadBalanceManager so it can close the migration window once all entities are done
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
  protected def onFinishSpontaneous(
    scheduleTick: Option[Tick] = None,
    destruct: Boolean = false
  ): Unit = {
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
      destruct = destruct
    )
    scheduleTick.foreach(
      tick =>
        // CRITICAL: Send ScheduleEvent to the SAME TimeManager that received the FinishEvent.
        // Using getTimeManager(currentTimeManagerType) could route to a different TM instance
        // (pool router), causing cross-TM scheduling inconsistencies and simulation hangs.
        currentTimeManager ! ScheduleEvent(
          tick = tick,
          actorRef = getPath,
          identify = Some(
            Identify(
              id = IdUtil.format(getEntityId),
              resourceId = IdUtil.format(properties.resourceId),
              classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
              actorRef = getPath,
              actorType = properties.actorType.toString
            )
          )
        )
    )
  }

  /** Sends a spontaneous event to itself. */
  protected def selfSpontaneous(): Unit =
    self ! SpontaneousEvent(currentTick, currentTimeManager)

  /** Schedules an event at a specific tick.
    * @param tick
    *   The tick at which the event should be scheduled
    */
  protected def scheduleEvent(tick: Tick): Unit =
    getTimeManager(currentTimeManagerType) ! ScheduleEvent(
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

  /** Reports an event to the reporting system.
    * @param event
    *   The report event
    */
  protected def report(event: ReportEvent): Unit = {
    // Prometheus: track report events by label
    if (event.label != null) {
      MetricsServer.eventsProcessed.labels(event.label).inc()
      event.label match {
        case "journey_started" =>
          val vehicleType = getClass.getSimpleName
          MetricsServer.journeysStarted.labels(vehicleType).inc()
        case "journey_completed" =>
          val vehicleType = getClass.getSimpleName
          MetricsServer.journeysCompleted.labels(vehicleType).inc()
        case _ => // other report labels tracked via eventsProcessed
      }
    }
    val defaultReportType = ReportTypeEnum.valueOf(
      Some(config.getString("htc.report-manager.default-strategy")).getOrElse("csv")
    )
    val reportType = if (state.getReporterType != null) {
      state.getReporterType
    } else {
      defaultReportType
    }
    if (reporters.contains(reportType)) {
      reporters(reportType) ! event
    } else {
      reporters(defaultReportType) ! event
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
  protected def getDependencyOption(entityId: String): Option[ShardActorId] = getRelationshipOption(entityId)

  /** @deprecated Use getRelationshipSafe instead. */
  @deprecated("Use getRelationshipSafe instead", "2.8.0")
  protected def getDependencySafe(entityId: String): ShardActorId = getRelationshipSafe(entityId)

  /** Gets the time manager actor reference (default: discrete-event).
    * @return
    *   The time manager actor reference
    */
  protected def getTimeManager: ActorRef = getDefaultTimeManager
}
