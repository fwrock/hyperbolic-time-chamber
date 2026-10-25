package org.interscity.htc
package core.actor.manager.loadbalance

import core.actor.manager.BaseManager
import core.actor.manager.loadbalance.allocation.{ LoadBalanceShardAllocator, ShardAllocatorRegistry, SpatialShardIdRegistry }
import core.actor.manager.loadbalance.allocation.*
import core.actor.manager.loadbalance.migration.{ MigrationRequestResult, MigrationStateStoreRegistry, MigrationWindowSubscriber, ShardMigrationCoordinator }
import core.actor.manager.loadbalance.strategy.{ BalancingStrategy, StrategyConfig, StrategyFactory }
import core.entity.control.loadbalance._
import core.entity.event.EntityEnvelopeEvent
import core.entity.event.control.loadbalance._
import core.entity.event.control.loadbalance.ShardRegionMetricsCollectedEvent
import core.entity.event.control.migration.{ AbortMigrationPreparationEvent, MigrationBatchRegisteredEvent, MigrationPreparationFailedEvent, MigrationRestoredAckEvent, MigrationRestoreAcceptedEvent, MigrationSnapshotsReadyEvent, MigrationWindowAckEvent, MigrationWindowCloseEvent, MigrationWindowOpenEvent, RegisterMigrationBatchEvent }
import core.entity.state.DefaultState
import core.enumeration.{ LoadBalanceStrategyEnum, ShardTypeEnum }
import core.util.ManagerConstantsUtil.LOAD_BALANCE_MANAGER_ACTOR_NAME
import core.util.IdUtil
import core.util.StringUtil
import LoadBalanceManager.*

import org.apache.pekko.actor.{ ActorRef, Cancellable, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.ClusterEvent.{ CurrentClusterState, MemberEvent, MemberRemoved, MemberUp, UnreachableMember }
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Publish
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ShardRegion }
import org.apache.pekko.pattern.{ ask, pipe }
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{ Failure, Success }

/** LoadBalanceManager
  *
  * Singleton manager that orchestrates the hybrid load balancing strategy across the cluster.
  * Coordinates with TimeManager (non-blocking), SimulationManager, and individual shards.
  *
  * Responsibilities:
  *   1. Execute the configured balancing strategy (Hybrid, Default, or Disabled) 2. Coordinate
  *      shard migrations without losing actors or messages 3. Synchronize with TimeManager during
  *      migrations (non-blocking) 4. Collect and aggregate metrics from shards 5. Trigger
  *      predictive rebalancing based on flow vectors
  *
  * Migration coordination protocol:
  *   1. LoadBalanceManager evaluates and decides migrations are needed 2. LoadBalanceManager →
  *      TimeManager: RequestMigrationPauseEvent 3. TimeManager finishes current tick's spontaneous
  *      events, pauses before advancing 4. TimeManager → LoadBalanceManager:
  *      MigrationSafeEvent(currentTick) 5. LoadBalanceManager executes all pending migrations 6.
  *      LoadBalanceManager → TimeManager: MigrationCompleteNotifyEvent 7. TimeManager resumes
  *      advancing ticks
  *
  * The manager is optional — when disabled, Pekko's default shard allocation is used.
  *
  * @param timeManager
  *   Reference to the GlobalTimeManager singleton
  * @param simulationManager
  *   Reference to the SimulationManager singleton
  * @param strategyType
  *   The balancing strategy to use
  * @param worldBounds
  *   The spatial bounds of the simulation world
  * @param strategyConfig
  *   Configuration for the strategy
  */
class LoadBalanceManager(
  timeManager: ActorRef,
  simulationManager: ActorRef,
  strategyType: LoadBalanceStrategyEnum,
  worldBounds: SpatialBounds,
  strategyConfig: StrategyConfig
) extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = LOAD_BALANCE_MANAGER_ACTOR_NAME
    ) {

  /** The active balancing strategy (None if disabled) */
  private var strategy: Option[BalancingStrategy] = None

  /** Migration coordinator for managing shard hand-offs */
  private val migrationCoordinator = new ShardMigrationCoordinator(
    maxConcurrentMigrations = strategyConfig.maxConcurrentMigrations
  )

  /** Periodic rebalance scheduler */
  private var rebalanceScheduler: Option[Cancellable] = None

  /** Periodic metrics collection scheduler */
  private var metricsScheduler: Option[Cancellable] = None

  /** Pekko Cluster reference */
  private lazy val cluster = Cluster(context.system)

  /** Self proxy for singleton messaging (lazy-initialized) */
  private var selfProxy: ActorRef = _

  /** Reference to TimeManager proxy for coordination */
  private var timeManagerProxy: ActorRef = _

  /** Pending migration plans waiting for TimeManager safe-to-migrate signal */
  private val pendingMigrationPlans: mutable.Queue[MigrationPlan] = mutable.Queue.empty

  /** Whether we're currently waiting for TimeManager to pause */
  private var awaitingMigrationPause: Boolean = false

  /** Custom shard allocation strategy that we control */
  private val shardAllocator: LoadBalanceShardAllocator = LoadBalanceShardAllocator()

  /** Shard type classification: shard ID → ShardTypeEnum. Static shards (Links, Nodes) are pinned;
    * dynamic shards (Cars, Buses) are migratable.
    */
  private val shardTypes: mutable.Map[String, ShardTypeEnum] = mutable.Map.empty

  // ── Migration Window State ────────────────────────────────────────────────

  /** Tracks the in-progress migration wave (open/restore/close phases). None when no dynamic
    * migration is active.
    */
  private var activeWave: Option[MigrationWaveState] = None
  private var migrationFailed = false
  private lazy val allocationRuntime = AllocationRuntime(context.system)
  private val allocationControllerId = java.util.UUID.randomUUID().toString
  private var allocationEpoch: Option[String] = None
  private val startupMigrations = mutable.Queue.empty[MigrationPlan]
  private case class PlacementAttempt(
    id: String,
    batchId: String,
    plan: MigrationPlan,
    targets: Seq[ResolvedMigrationTarget],
    startedAt: Long = System.nanoTime(),
    skipped: Boolean = false,
    var round: Long = 0L,
    var inFlight: Boolean = false,
    var verified: Boolean = false,
    var completionQueued: Boolean = false,
    var installed: Boolean = true,
    var poll: Option[Cancellable] = None
  )
  private val placementAttempts = mutable.Map.empty[String, PlacementAttempt]

  /** Lazy DistributedPubSub mediator reference for broadcasting window events. */
  private lazy val mediatorRef: ActorRef = DistributedPubSub(context.system).mediator

  // ── Per-wave migration tracking state ────────────────────────────────────

  /** Per-wave migration tracking state. */
  private enum WavePhase {
    case Resolving, Registering, Saving, Opening, Restoring, Closing
  }

  private case class MigrationWaveState(
    batchId: String,
    plans: Seq[MigrationPlan],
    allEntityIds: Set[String],
    pendingWindowOpenNodeAcks: mutable.Set[String],
    pendingRestoreEntityAcks: mutable.Set[String],
    pendingWindowCloseNodeAcks: mutable.Set[String],
    entityIdsByShard: Map[String, Set[String]],
    pendingPlacementShards: mutable.Set[String],
    var destinations: Map[String, Seq[ResolvedMigrationTarget]] = Map.empty,
    var phase: WavePhase = WavePhase.Resolving
  )

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override def onStart(): Unit = {
    logInfo(s"LoadBalanceManager starting with strategy: $strategyType")

    // Initialize strategy
    strategy = StrategyFactory.create(strategyType)
    strategy.foreach {
      s =>
        s.initialize(worldBounds, strategyConfig)
        logInfo(s"Strategy '${s.name}' initialized with bounds: $worldBounds")
    }

    // Register existing cluster nodes
    registerClusterNodes()

    // Subscribe to cluster membership events for dynamic joins/leaves
    cluster.subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])

    // Register the shard allocator so ActorCreatorUtil uses it for future shard creations
    if (!allocationRuntime.enabled) ShardAllocatorRegistry.register(shardAllocator)

    // Schedule periodic rebalancing
    if (strategy.isDefined) {
      scheduleRebalancing()
      scheduleMetricsCollection()
    }

    // Initialize proxies
    selfProxy = if (allocationRuntime.enabled) self else createSingletonProxy(LOAD_BALANCE_MANAGER_ACTOR_NAME)
    timeManagerProxy = timeManager

    // Notify SimulationManager that we're ready
    if (allocationRuntime.enabled) {
      val request = BindAllocationController(java.util.UUID.randomUUID().toString, "", self, allocationControllerId)
      allocationRuntime.bind(request).map(e => AllocationBound(e.epoch))
        .recover { case scala.util.control.NonFatal(error) => AllocationStartupFailed(error.getMessage) }.pipeTo(self)
    } else simulationManager ! LoadBalanceReadyEvent(getSelfProxy)

    logInfo(
      s"LoadBalanceManager ready. Strategy: ${strategy.map(_.name).getOrElse("disabled")}, " +
        s"Cluster nodes: ${cluster.state.members.count(_.status == MemberStatus.Up)}"
    )
  }

  // ── Message Handling ───────────────────────────────────────────────────────

  override def handleEvent: Receive = {
    case _: StopSimulationEvent => handleStopSimulation()
    case _ if migrationFailed => ()
    case AllocationBound(epoch) =>
      allocationEpoch = Some(epoch)
      simulationManager ! LoadBalanceReadyEvent(getSelfProxy)
      startupMigrations.dequeueAll(_ => true).foreach(plan => self ! RequestMigrationEvent(plan))
    case AllocationStartupFailed(reason) => failMigrationPreparation(reason)
    case e: AllocationAuthorityFailure if e.controllerId == allocationControllerId => failMigrationPreparation(e.reason)
    case _: AllocationAuthorityFailure => ()
    case e: DestinationsResolved => handleDestinationsResolved(e)
    case e: IntentAcknowledged => handleIntentAcknowledged(e)
    // Load balance events
    case event: UpdateLoadMetricsEvent            => handleUpdateMetrics(event)
    case event: RegisterSpatialEntityEvent        => handleRegisterEntity(event)
    case event: RegisterSpatialEntitiesBatchEvent => handleBatchRegisterEntities(event)
    case event: RequestMigrationEvent if allocationRuntime.enabled && allocationEpoch.isEmpty => startupMigrations.enqueue(event.plan)
    case event: RequestMigrationEvent             => handleMigrationRequest(event)
    case event: MigrationCompleteEvent            => handleMigrationComplete(event)
    case event: PlacementPoll => pollMigrationPlacement(event)
    case event: PlacementObserved => handlePlacementObserved(event)
    case _: ShardRegion.StartEntityAck => ()
    case event: TriggerRebalanceEvent             => handleTriggerRebalance(event)
    case _: CollectAndFeedMetricsEvent            => handleCollectAndFeedMetrics()
    case event: ShardRegionMetricsCollectedEvent  => handleShardRegionMetricsCollected(event)

    // TimeManager coordination events
    case event: MigrationSafeEvent => handleMigrationSafe(event)

    case event: MigrationBatchRegisteredEvent => handleBatchRegistered(event)
    case event: MigrationSnapshotsReadyEvent => handleSnapshotsReady(event)
    case event: MigrationPreparationFailedEvent =>
      activeWave.filter(_.batchId == event.batchId).foreach(_ => failMigrationPreparation(event.reason))
    case event: MigrationWindowAckEvent   => handleWindowAck(event)
    case event: MigrationRestoreAcceptedEvent => handleRestoredAck(event)
    case _: MigrationRestoredAckEvent => ()

    case state: CurrentClusterState =>
      state.members.filter(_.status == MemberStatus.Up).foreach {
        m =>
          strategy.foreach(_.registerNode(m.address))
      }
      logInfo(
        s"CurrentClusterState received: ${state.members.size} members, " +
          s"${state.members.count(_.status == MemberStatus.Up)} Up"
      )

    case MemberUp(member) =>
      logInfo(s"Cluster member joined: ${member.address}")
      strategy.foreach(_.registerNode(member.address))

    case MemberRemoved(member, _) =>
      logInfo(s"Cluster member removed: ${member.address}")
      strategy.foreach(_.removeNode(member.address))

    case _: UnreachableMember =>
    case _: MemberEvent => ()

  }

  /** Receives load metrics from shards and feeds them to the strategy. */
  private def handleUpdateMetrics(event: UpdateLoadMetricsEvent): Unit =
    strategy.foreach(_.updateMetrics(event.metrics))

  /** Registers a spatial entity with the partitioning structure.
    *
    * NOTE: This performs a LOGICAL assignment only — it maps the entity to a shard ID in the
    * Quadtree/strategy based on spatial position. It does NOT create the shard or the actor. Shard
    * and actor creation is handled by CreatorLoadData/LoadDataManager. The returned shard ID should
    * be used by the creation pipeline to route the entity to the correct shard region.
    *
    * Also classifies the entity's shard type (Static/Dynamic) based on the entity's actor class.
    */
  private def handleRegisterEntity(event: RegisterSpatialEntityEvent): Unit =
    strategy.foreach {
      s =>
        val shardId = s.assignShard(event.entity)
        logDebug(
          s"Entity '${event.entity.spatialEntityId}' mapped to shard '$shardId' (logical assignment)"
        )

        val entityType = classifyEntityType(event.entity)
        shardTypes.get(shardId) match {
          case Some(existing) if existing != entityType =>
            shardTypes.put(shardId, ShardTypeEnum.Mixed)
          case None =>
            shardTypes.put(shardId, entityType)
          case _ =>
        }

        sender() ! ShardAssignmentResponse(event.entity.spatialEntityId, shardId)
    }

  /** Registers a batch of spatial entities and replies with all assignments at once.
    *
    * This is the primary path used by [[core.actor.manager.load.CreatorLoadData]] during entity
    * creation. Processing a batch in a single message avoids the overhead of individual ask/reply
    * per entity (which would be 1000+ round-trips per chunk).
    *
    * For each entity, calls [[BalancingStrategy.assignShard]] and classifies the shard type. The
    * response includes both shard assignments and entity positions so that the creation pipeline
    * can resolve node positions for links and vehicles.
    */
  private def handleBatchRegisterEntities(event: RegisterSpatialEntitiesBatchEvent): Unit =
    strategy match {
      case Some(s) =>
        val assignments = mutable.Map[String, String]()
        val positions = mutable.Map[String, Array[Double]]()

        event.entities.foreach {
          entity =>
            val shardId = s.assignShard(entity)

            assignments.put(entity.spatialEntityId, shardId)
            val pos = entity.position
            positions.put(entity.spatialEntityId, Array(pos._1, pos._2))

            val entityType = classifyEntityType(entity)
            shardTypes.get(shardId) match {
              case Some(existing) if existing != entityType =>
                shardTypes.put(shardId, ShardTypeEnum.Mixed)
              case None =>
                shardTypes.put(shardId, entityType)
              case _ => // Same type, no change
            }
        }

        logDebug(
          s"Batch registered ${event.entities.size} entities for batch '${event.batchId}'. " +
            s"Assigned to ${assignments.values.toSet.size} distinct shards."
        )

        sender() ! BatchShardAssignmentResponse(
          assignments = assignments.toMap,
          positions = positions.toMap,
          batchId = event.batchId,
          chunkIndex = event.chunkIndex
        )

      case None =>
        logWarn(
          s"Batch registration for '${event.batchId}' but strategy is disabled. " +
            s"Replying with empty assignments."
        )
        sender() ! BatchShardAssignmentResponse(
          assignments = Map.empty,
          positions = Map.empty,
          batchId = event.batchId,
          chunkIndex = event.chunkIndex
        )
    }

  /** Handles an explicit migration request (e.g., from predictive analysis or periodic rebalance).
    *
    * Migrations are queued and only executed after the TimeManager confirms it has paused at a safe
    * tick boundary. This ensures no spontaneous events are in-flight.
    */
  private def handleMigrationRequest(event: RequestMigrationEvent): Unit = {
    val result = migrationCoordinator.requestMigration(event.plan)
    result match {
      case MigrationRequestResult.Started =>
        enqueueAdmittedMigration(event.plan)

      case MigrationRequestResult.Queued(pos) =>
        logInfo(s"Migration queued at position $pos: shard '${event.plan.shardId}'")

      case MigrationRequestResult.AlreadyMigrating =>
        logWarn(s"Shard '${event.plan.shardId}' is already being migrated")

      case MigrationRequestResult.Rejected(reason) =>
        logWarn(s"Migration rejected for shard '${event.plan.shardId}': $reason")
    }
  }

  private def enqueueAdmittedMigration(plan: MigrationPlan): Unit = {
    logInfo(
      s"Migration queued for execution: shard '${plan.shardId}' " +
        s"from ${plan.sourceNode} to ${plan.targetNode} (reason: ${plan.reason})"
    )
    pendingMigrationPlans.enqueue(plan)
    requestTimeManagerPause()
  }

  private def continueMigrationQueue(): Unit =
    if (activeWave.isEmpty && !awaitingMigrationPause) {
      if (pendingMigrationPlans.nonEmpty) requestTimeManagerPause()
      else if (migrationCoordinator.activeMigrationCount == 0) {
        timeManagerProxy ! MigrationCompleteNotifyEvent()
      }
    }

  /** Requests the TimeManager to pause at a safe tick boundary.
    *
    * Only sends one request at a time — if we're already waiting, additional migration plans just
    * queue up and will be executed once the TimeManager signals safety.
    */
  private def requestTimeManagerPause(): Unit =
    if (activeWave.isEmpty && !awaitingMigrationPause && pendingMigrationPlans.nonEmpty) {
      awaitingMigrationPause = true
      val shardIds = pendingMigrationPlans.map(_.shardId).toSet
      logInfo(s"Requesting TimeManager to pause for migration of shards: $shardIds")
      timeManagerProxy ! RequestMigrationPauseEvent(
        shardIds = shardIds,
        requester = getSelfProxy
      )
    }

  /** Handles the TimeManager confirming it has paused at a safe tick boundary.
    *
    * Separates static shard migrations (no window needed) from dynamic shard migrations (require
    * the distributed migration window protocol to guarantee cross-node snapshot delivery before
    * Pekko hands off the shard).
    *
    * Dynamic plan flow:
    *   1. Register batch with SnapshotManager and await registration ACK 2. Notify entities to
    *      freeze and serialize state 3. Wait for the complete saved-snapshot barrier
    *      4. Wait for open ACKs from all nodes → then trigger Pekko
    *      rebalance per plan 5. Verify placement and accepted restores → then broadcast window-close 6. Wait
    *      for close ACKs → then notify TimeManager to resume
    */
  private def handleMigrationSafe(event: MigrationSafeEvent): Unit = {
    if (!awaitingMigrationPause || activeWave.isDefined) {
      logWarn(s"Ignoring unsolicited migration-safe notification at tick ${event.currentTick}")
      return
    }
    logInfo(
      s"TimeManager paused at tick ${event.currentTick}. Executing ${pendingMigrationPlans.size} pending migrations."
    )
    awaitingMigrationPause = false

    if (pendingMigrationPlans.isEmpty) {
      logWarn("Received MigrationSafeEvent but no pending migrations. Resuming TimeManager.")
      timeManagerProxy ! MigrationCompleteNotifyEvent()
      return
    }

    val plansToExecute = pendingMigrationPlans.dequeueAll(
      _ => true
    )

    val staticPlans = plansToExecute.filter {
      p =>
        shardTypes.getOrElse(p.shardId, ShardTypeEnum.Dynamic) == ShardTypeEnum.Static
    }
    val dynamicPlans = plansToExecute.filter {
      p =>
        shardTypes.getOrElse(p.shardId, ShardTypeEnum.Dynamic) != ShardTypeEnum.Static
    }
    staticPlans.foreach(executeMigration)

    if (dynamicPlans.isEmpty) {
      if (staticPlans.isEmpty) timeManagerProxy ! MigrationCompleteNotifyEvent()
      return
    }

    val batchId = java.util.UUID.randomUUID().toString.take(12)
    val entityIdsByShard = dynamicPlans.map(p => p.shardId ->
      SpatialShardIdRegistry.getEntitiesInShard(p.shardId).map(IdUtil.format)).toMap
    val allEntityIds = entityIdsByShard.values.flatten.toSet

    val clusterNodeAddrs: mutable.Set[String] = mutable.Set.empty ++
      cluster.state.members
        .filter(_.status == MemberStatus.Up)
        .map(_.address.toString)

    activeWave = Some(
      MigrationWaveState(
        batchId = batchId,
        plans = dynamicPlans.toSeq,
        allEntityIds = allEntityIds,
        pendingWindowOpenNodeAcks = clusterNodeAddrs,
        pendingRestoreEntityAcks = mutable.Set.empty ++ allEntityIds,
        pendingWindowCloseNodeAcks = mutable.Set.empty,
        entityIdsByShard = entityIdsByShard,
        pendingPlacementShards = mutable.Set.empty ++ dynamicPlans.map(_.shardId)
      )
    )

    if (allEntityIds.isEmpty) {
      failMigrationPreparation("dynamic migration has no known source membership")
      return
    }
    Future.sequence(dynamicPlans.toVector.map(plan => resolveMigrationDestinationsAsync(plan).map(plan.shardId -> _)))
      .map(results => DestinationsResolved(batchId, results)).pipeTo(self)
  }

  private def handleDestinationsResolved(event: DestinationsResolved): Unit = {
    val current = activeWave.filter(w => w.batchId == event.batchId && w.phase == WavePhase.Resolving)
    if (current.isEmpty) return
    val wave = current.get
    event.results.collectFirst { case (_, Left(reason)) => reason } match {
      case Some(reason) =>
        failMigrationPreparation(reason)
        return
      case None => wave.destinations = event.results.collect { case (id, Right(targets)) => id -> targets }.toMap
    }
    wave.phase = WavePhase.Registering
    MigrationStateStoreRegistry.getSnapshotManager match {
      case Some(smRef) => smRef ! RegisterMigrationBatchEvent(wave.batchId, wave.allEntityIds, getSelfProxy)
      case None =>
        failMigrationPreparation("SnapshotManager proxy is not registered")
        return
    }

    logInfo(
      s"Migration wave '${wave.batchId}': ${wave.plans.size} dynamic plan(s), ${wave.allEntityIds.size} entities"
    )
  }

  private def handleBatchRegistered(event: MigrationBatchRegisteredEvent): Unit =
    activeWave.filter(w => w.batchId == event.batchId && w.phase == WavePhase.Registering).foreach { wave =>
      wave.phase = WavePhase.Saving
      wave.plans.foreach { plan =>
        notifyEntitiesForMigrationWithBatch(plan.shardId, plan.targetNode.toString, wave.batchId)
      }
    }

  private def handleSnapshotsReady(event: MigrationSnapshotsReadyEvent): Unit =
    activeWave.filter(w => w.batchId == event.batchId && w.phase == WavePhase.Saving).foreach { wave =>
      if (event.entityIds != wave.allEntityIds) failMigrationPreparation("snapshot barrier membership mismatch")
      else {
        wave.phase = WavePhase.Opening
        logInfo(
          s"Broadcasting MigrationWindowOpenEvent for batch '${wave.batchId}' " +
            s"(${wave.allEntityIds.size} entities, ${wave.pendingWindowOpenNodeAcks.size} nodes)"
        )
        mediatorRef ! Publish(
          MigrationWindowSubscriber.TOPIC,
          MigrationWindowOpenEvent(
            batchId = wave.batchId,
            entityIds = wave.allEntityIds,
            lbmRef = getSelfProxy
          )
        )
      }
    }

  private def failMigrationPreparation(reason: String): Unit = {
    logError(s"Migration preparation failed; stopping the simulation: $reason")
    migrationFailed = true
    abortPreparedSources()
    activeWave = None
    pendingMigrationPlans.clear()
    startupMigrations.clear()
    awaitingMigrationPause = false
    migrationCoordinator.abortAll()
    clearPlacementAttempts()
    rebalanceScheduler.foreach(_.cancel())
    metricsScheduler.foreach(_.cancel())
    timeManagerProxy ! StopSimulationEvent()
    simulationManager ! StopSimulationEvent()
  }

  private def abortPreparedSources(): Unit =
    activeWave.foreach { wave =>
      wave.plans.foreach { plan =>
        SpatialShardIdRegistry.getEntitiesInShard(plan.shardId).foreach { id =>
          SpatialShardIdRegistry.getEntityClassName(id).foreach { className =>
            try {
              ClusterSharding(context.system).shardRegion(className) !
                EntityEnvelopeEvent(id, AbortMigrationPreparationEvent(wave.batchId))
            } catch {
              case e: IllegalArgumentException =>
                logWarn(s"Cannot unfreeze '$id' during shutdown: ${e.getMessage}")
            }
          }
        }
      }
    }

  /** Handles a MigrationWindowAckEvent from a cluster node subscriber.
    *
    * Open ACKs: count down. When all nodes ACKed → trigger Pekko rebalance per plan. Close ACKs:
    * count down. All close ACKs permit queue continuation; resume waits for the queue to finish.
    */
  private def handleWindowAck(event: MigrationWindowAckEvent): Unit =
    activeWave.filter(_.batchId == event.batchId) match {
      case None =>
        logWarn(
          s"MigrationWindowAckEvent for unknown batch '${event.batchId}' (phase=${event.phase}) — ignoring"
        )
      case Some(wave) =>
        event.phase match {
          case MigrationWindowSubscriber.PHASE_OPEN if wave.phase == WavePhase.Opening &&
              wave.pendingWindowOpenNodeAcks.remove(event.nodeAddress) =>
            logDebug(
              s"Window open ACK from '${event.nodeAddress}' " +
                s"(remaining: ${wave.pendingWindowOpenNodeAcks.size})"
            )
            if (wave.pendingWindowOpenNodeAcks.isEmpty) {
              wave.phase = WavePhase.Restoring
              logInfo(
                s"All nodes ACKed window open for batch '${wave.batchId}'. " +
                  s"Triggering Pekko shard rebalance for ${wave.plans.size} plans."
              )
              wave.plans.foreach(triggerPekkoHandoff)
            }

          case MigrationWindowSubscriber.PHASE_CLOSE if wave.phase == WavePhase.Closing &&
              wave.pendingWindowCloseNodeAcks.remove(event.nodeAddress) =>
            logDebug(
              s"Window close ACK from '${event.nodeAddress}' " +
                s"(remaining: ${wave.pendingWindowCloseNodeAcks.size})"
            )
            if (wave.pendingWindowCloseNodeAcks.isEmpty) {
              logInfo(
                s"All nodes ACKed window close for batch '${wave.batchId}'. " +
                  s"Continuing admitted migrations."
              )
              activeWave = None
              continueMigrationQueue()
            }

          case _ => ()
        }
    }

  /** A restore confirmation alone cannot release admission or close the window without placement evidence. */
  private def handleRestoredAck(event: MigrationRestoreAcceptedEvent): Unit =
    activeWave.filter(_.batchId == event.batchId) match {
      case None =>
        logDebug(
          s"MigrationRestoreAcceptedEvent for '${event.entityId}' in batch '${event.batchId}' — " +
            s"no active wave (already closed or not a migration entity)"
        )
      case Some(wave) if wave.phase == WavePhase.Restoring && wave.pendingRestoreEntityAcks.remove(event.entityId) =>
        logDebug(
          s"Entity '${event.entityId}' restored. " +
            s"Batch '${event.batchId}': ${wave.pendingRestoreEntityAcks.size} entities remaining."
        )
        wave.plans.foreach(plan => maybeCompletePlacement(plan.shardId))
        maybeCloseMigrationWindow(wave)
      case _ => ()
    }

  private def maybeCloseMigrationWindow(wave: MigrationWaveState): Unit =
    if (wave.phase == WavePhase.Restoring && wave.pendingRestoreEntityAcks.isEmpty &&
        wave.pendingPlacementShards.isEmpty) {
      wave.phase = WavePhase.Closing
      wave.pendingWindowCloseNodeAcks ++= cluster.state.members.filter(_.status == MemberStatus.Up).map(_.address.toString)
      mediatorRef ! Publish(MigrationWindowSubscriber.TOPIC,
        MigrationWindowCloseEvent(batchId = wave.batchId, lbmRef = getSelfProxy))
    }

  private def handleMigrationComplete(event: MigrationCompleteEvent): Unit = {
    val attempt = placementAttempts.get(event.shardId).filter(a => a.id == event.attemptId &&
      a.batchId == event.batchId && a.completionQueued && a.verified)
    if (sender() != self || attempt.isEmpty || !event.success ||
        !migrationCoordinator.isMigrating(event.shardId)) return
    placementAttempts.remove(event.shardId).foreach(_.poll.foreach(_.cancel()))
    val nextPlan = migrationCoordinator.completeMigration(event.shardId, event.success)
    logInfo(s"Migration '${event.shardId}' ${if (attempt.get.skipped) "skipped (static)" else "placement and restore verified"}: ${event.durationMs}ms")
    activeWave.filter(_.batchId == event.batchId).foreach { wave =>
      wave.pendingPlacementShards -= event.shardId
      maybeCloseMigrationWindow(wave)
    }

    nextPlan.foreach(enqueueAdmittedMigration)

    val stats = migrationCoordinator.getStats
    continueMigrationQueue()

    logInfo(
      s"Migration stats: active=${stats.activeMigrations}, " +
        s"pending=${stats.pendingMigrations}, " +
        s"completed=${stats.completedMigrations}, " +
        s"failed=${stats.failedMigrations}, " +
        s"avgTime=${stats.avgMigrationTimeMs}ms"
    )
  }

  /** Handles periodic or manual rebalancing trigger. */
  private def handleTriggerRebalance(event: TriggerRebalanceEvent): Unit =
    strategy.foreach {
      s =>
        logDebug(s"Evaluating rebalance (reason: ${event.reason})")
        val migrations = s.evaluate()

        if (migrations.nonEmpty) {
          logInfo(s"Rebalance generated ${migrations.size} migration(s)")
          migrations.foreach {
            plan =>
              getSelfProxy ! RequestMigrationEvent(plan)
          }
        }
    }

  /** Graceful shutdown. */
  private def handleStopSimulation(): Unit = {
    logInfo("LoadBalanceManager stopping. Aborting active migrations.")

    cluster.unsubscribe(self)
    abortPreparedSources()

    ShardAllocatorRegistry.clear()
    SpatialShardIdRegistry.clear()
    shardAllocator.clearAll()

    rebalanceScheduler.foreach(_.cancel())
    metricsScheduler.foreach(_.cancel())

    migrationCoordinator.abortAll()
    clearPlacementAttempts()

    awaitingMigrationPause = false
    activeWave = None

    strategy.foreach(_.shutdown())

    strategy.foreach {
      s =>
        logInfo(s"Final stats: ${s.getStats}")
    }

    selfDestruct()
  }

  /** Notifies entities in a shard to save their state to the SnapshotManager (migration window).
    *
    * Includes `batchId` and `lbmRef` in the PrepareForMigrationEvent so entities can:
    *   1. Send [[core.entity.event.control.migration.SaveMigrationSnapshotEvent]] to SM 2. Later
    *      confirm restoration to SM, which notifies LBM only after validating the confirmation
    */
  protected def notifyEntitiesForMigrationWithBatch(
    shardId: String,
    targetNode: String,
    batchId: String
  ): Unit = {
    val entityIds = SpatialShardIdRegistry.getEntitiesInShard(shardId)
    if (entityIds.nonEmpty) {
      logInfo(
        s"Notifying ${entityIds.size} entities in shard '$shardId' to save state " +
          s"(batch='$batchId')"
      )
      entityIds.foreach {
        eid =>
          try {
            val className = SpatialShardIdRegistry.getEntityClassName(eid).getOrElse(
              throw new IllegalStateException(s"No actor class registered for '$eid'"))
            val region = ClusterSharding(context.system).shardRegion(className)
            region ! EntityEnvelopeEvent(
              eid,
              PrepareForMigrationEvent(
                shardId = shardId,
                targetNode = targetNode,
                batchId = batchId,
                lbmRef = getSelfProxy
              )
            )
          } catch {
            case e: Exception =>
              getSelfProxy ! MigrationPreparationFailedEvent(batchId, eid,
                s"Cannot prepare source: ${e.getMessage}")
          }
      }
    } else {
      logDebug(s"No entity IDs tracked for shard '$shardId' (batch='$batchId')")
    }
  }

  /** Lazy-initialized self proxy to avoid NPE when accessed before onStart completes. */
  private def getSelfProxy: ActorRef = {
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(LOAD_BALANCE_MANAGER_ACTOR_NAME)
    }
    selfProxy
  }

  /** Register existing cluster nodes with the strategy. */
  private def registerClusterNodes(): Unit =
    strategy.foreach {
      s =>
        cluster.state.members.foreach {
          member =>
            if (member.status == MemberStatus.Up) {
              s.registerNode(member.address)
            }
        }
    }

  /** Schedule periodic rebalancing. */
  private def scheduleRebalancing(): Unit = {
    val interval = strategyConfig.rebalanceIntervalSeconds.seconds
    rebalanceScheduler = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = interval,
        delay = interval
      ) {
        () =>
          getSelfProxy ! TriggerRebalanceEvent(reason = "periodic")
      }
    )
    logInfo(s"Rebalance scheduled every ${strategyConfig.rebalanceIntervalSeconds}s")
  }

  /** Schedule periodic metrics collection.
    *
    * Every 60 seconds (first at 30s) we query each registered shard region for its current shard
    * set and feed the shard→node assignments + entity counts into the strategy's kd-tree.
    *
    * We use ClusterSharding.shardTypeNames (no ask needed) to enumerate registered types, then
    * ShardRegion.GetShardRegionState (ask) per region to discover which shards each node currently
    * owns. This is robust against the LBM starting after the shard regions have already been
    * registered with the default allocator.
    */
  private def scheduleMetricsCollection(): Unit = {
    val interval = 60.seconds
    metricsScheduler = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = 30.seconds,
        delay = interval
      ) {
        () =>
          getSelfProxy ! CollectAndFeedMetricsEvent()
      }
    )
  }

  /** Collect shard location + entity count data and feed it into the strategy's kd-tree.
    *
    * Queries every registered shard region via `ShardRegion.GetShardRegionState` (ask) to discover
    * which shards each cluster node currently owns. This approach is correct regardless of whether
    * the LBM's custom allocator was used at region startup time — it interrogates Pekko's actual
    * runtime state rather than relying on callbacks that may never fire (e.g. when regions were
    * registered with the default allocation strategy).
    *
    * After this method the kd-tree has:
    *   - shardAssignment populated (so getImbalanceRatio works)
    *   - shardMetricsMap populated (so rebalance() weights shards correctly)
    */
  private def handleCollectAndFeedMetrics(): Unit =
    strategy.foreach {
      s =>
        val sharding = ClusterSharding(context.system)
        val regionNames = sharding.shardTypeNames

        if (regionNames.isEmpty) {
          logInfo(
            "Shard metrics updated: 0 shard-node assignments across 0 regions, " +
              s"${s.getShardEntityCounts.size} entity-count entries. " +
              s"Imbalance ratio: ${s.getStats.get("imbalanceRatio").getOrElse("n/a")} " +
              "(no shard regions registered yet)"
          )
        } else {
          implicit val timeout: Timeout = Timeout(5.seconds)
          var totalAssigned = 0

          regionNames.foreach {
            typeName =>
              try {
                val regionRef = sharding.shardRegion(typeName)
                (regionRef ? ShardRegion.GetShardRegionState).onComplete {
                  case Success(ShardRegion.CurrentShardRegionState(shards)) =>
                    val addr =
                      if (regionRef.path.address.hasLocalScope) cluster.selfAddress
                      else regionRef.path.address
                    shards.foreach {
                      shardState =>
                        s.recordShardLocation(shardState.shardId, addr)
                    }
                    getSelfProxy ! ShardRegionMetricsCollectedEvent(typeName, shards.size)

                  case Success(other) =>
                    logWarn(s"Unexpected response from shard region '$typeName': $other")

                  case Failure(ex) =>
                    logWarn(s"Failed to query shard region '$typeName': ${ex.getMessage}")
                }
              } catch {
                case ex: Exception =>
                  logWarn(s"Could not reach shard region '$typeName': ${ex.getMessage}")
              }
          }

          val entityCounts = s.getShardEntityCounts
          entityCounts.foreach {
            case (shardId, count) =>
              s.updateMetrics(
                ShardMetrics(
                  shardId = shardId,
                  entityCount = count,
                  totalWeight = count.toDouble
                )
              )
          }

          val imbalanceRatio = s.getStats.get("imbalanceRatio").getOrElse("n/a")
          logInfo(
            s"Shard metrics collection triggered for ${regionNames.size} region type(s). " +
              s"${entityCounts.size} entity-count entries fed. " +
              s"Imbalance ratio: $imbalanceRatio"
          )
        }
    }

  /** Handles the async acknowledgement from a shard region state query.
    *
    * Fired after `ShardRegion.GetShardRegionState` resolves for a given shard type. Purely
    * informational — the actual `recordShardLocation` calls happen in the Future callback inside
    * `handleCollectAndFeedMetrics`.
    */
  private def handleShardRegionMetricsCollected(event: ShardRegionMetricsCollectedEvent): Unit =
    logDebug(s"Shard region '${event.typeName}': ${event.shardCount} shards located.")

  /** Execute the actual shard migration via Pekko cluster sharding.
    *
    * Uses the [[LoadBalanceShardAllocator]] to influence Pekko's shard coordination:
    *   1. Check shard type — only Dynamic/Mixed shards can be migrated 2. Find the target region
    *      ActorRef for the target node address 3. Update the allocator's desired allocation map 4.
    *      Mark the shard for rebalance, which Pekko picks up on the next coordinator tick 5.
    *      Monitor completion via a periodic check
    *
    * Static shards (Links, Nodes, TrafficSignals) are skipped because they anchor the spatial
    * partition and should no//t move.
    *
    * NOTE: For dynamic shards use [[triggerPekkoHandoff]] after the migration window is open. This
    * method is kept for static shard handling.
    */
  private def executeMigration(plan: MigrationPlan): Unit = {
    // Check shard type — Static shards should not be migrated
    val shardType = shardTypes.getOrElse(plan.shardId, ShardTypeEnum.Dynamic)
    if (shardType == ShardTypeEnum.Static) {
      logInfo(
        s"Skipping migration of static shard '${plan.shardId}' (infrastructure actors are pinned)"
      )
      val attempt = PlacementAttempt(java.util.UUID.randomUUID().toString, "", plan, Seq.empty,
        skipped = true, verified = true, completionQueued = true)
      placementAttempts(plan.shardId) = attempt
      self ! MigrationCompleteEvent(plan.shardId, attemptId = attempt.id)
      return
    }

    triggerPekkoHandoff(plan)
  }

  /** Triggers the actual Pekko shard rebalance for a dynamic plan.
    *
    * Called after all cluster nodes have ACKed the migration window open — meaning all nodes have
    * set isMigrationActive = true and entities on the target node will query the SnapshotManager
    * for their snapshot upon recreation.
    *
    * Notifying entities to save state (PrepareForMigrationEvent) was already done in
    * handleMigrationSafe; this method only tells the Pekko ShardCoordinator to move the shard and
    * then monitors completion.
    */
  protected def triggerPekkoHandoff(plan: MigrationPlan): Unit = {
    if (migrationFailed) return
    logInfo(
      s"Triggering Pekko hand-off: shard '${plan.shardId}' (type=${shardTypes
          .getOrElse(plan.shardId, ShardTypeEnum.Dynamic)}) → ${plan.targetNode}"
    )

    val planned = activeWave.flatMap(_.destinations.get(plan.shardId))
    if (allocationRuntime.enabled) {
      val targets = planned.getOrElse(Seq.empty)
      val attempt = PlacementAttempt(java.util.UUID.randomUUID().toString, activeWave.get.batchId, plan,
        targets, installed = false)
      placementAttempts(plan.shardId) = attempt
      val request = InstallAllocationIntent(java.util.UUID.randomUUID().toString, allocationEpoch.get,
        allocationControllerId, attempt.batchId, attempt.id, plan.shardId, plan.sourceNode.toString,
        plan.targetNode.toString, targets.map(t => AllocationTarget(t.typeName, t.sourceRegion, t.region)).toVector)
      allocationRuntime.request(request).map {
        case e: AllocationIntentInstalled if e.requestId == request.requestId && e.epoch == request.epoch &&
            e.batchId == request.batchId && e.attemptId == request.attemptId =>
          IntentAcknowledged(plan.shardId, request.batchId, request.attemptId, retiring = false, None)
        case e: AllocationRejected => IntentAcknowledged(plan.shardId, request.batchId, request.attemptId, false, Some(e.reason))
        case _ => IntentAcknowledged(plan.shardId, request.batchId, request.attemptId, false, Some("uncorrelated allocation intent reply"))
      }.recover { case scala.util.control.NonFatal(error) =>
        IntentAcknowledged(plan.shardId, request.batchId, request.attemptId, false, Some(error.getMessage))
      }.pipeTo(self)
      return
    }
    resolveMigrationDestinations(plan) match {
      case Right(targets) if planned.contains(targets) && targets.nonEmpty =>
        targets.foreach { target =>
          shardAllocator.forType(target.typeName).requestRebalance(plan.shardId, target.region)
        }
        logInfo(
          s"Shard '${plan.shardId}' marked for rebalance in allocator. " +
            s"Pekko ShardCoordinator will pick it up on next rebalance cycle."
        )
        startMigrationPlacementMonitoring(plan, targets)

      case Left(reason) => failMigrationPreparation(reason)
      case _ => failMigrationPreparation(s"Destination registration changed for shard '${plan.shardId}'")
    }
  }

  protected def resolveMigrationDestinations(plan: MigrationPlan): Either[String, Seq[ResolvedMigrationTarget]] = {
    migrationTypeNames(plan).flatMap { typeNames =>
      val targets = typeNames.map { typeName =>
        shardAllocator.forType(typeName).resolvePlacement(plan.shardId, plan.sourceNode, plan.targetNode,
          cluster.selfAddress).map { case (source, target) => ResolvedMigrationTarget(typeName, target, source) }
      }
      targets.collectFirst { case Left(reason) => reason } match {
        case Some(reason) => Left(reason)
        case None => Right(targets.collect { case Right(target) => target })
      }
    }
  }

  private def migrationTypeNames(plan: MigrationPlan): Either[String, Seq[String]] = {
    if (!cluster.state.members.exists(m => m.status == MemberStatus.Up && m.address == plan.sourceNode))
      return Left(s"Migration source ${plan.sourceNode} is not an Up cluster member")
    if (!cluster.state.members.exists(m => m.status == MemberStatus.Up && m.address == plan.targetNode))
      return Left(s"Migration target ${plan.targetNode} is not an Up cluster member")
    val ids = SpatialShardIdRegistry.getEntitiesInShard(plan.shardId).toSeq.sorted
    val classes = ids.map(SpatialShardIdRegistry.getEntityClassName)
    if (ids.isEmpty || classes.exists(_.isEmpty))
      return Left(s"Incomplete entity/type membership for shard '${plan.shardId}'")
    val typeNames = classes.flatten.map(StringUtil.getModelClassName).distinct.sorted
    if (typeNames.exists { name =>
      val lower = name.split('.').last.toLowerCase
      lower.contains("node") || lower.contains("link") || lower.contains("signal")
    }) return Left(s"Shard '${plan.shardId}' contains pinned infrastructure; mixed infrastructure migration is unsupported")
    Right(typeNames)
  }

  private def resolveMigrationDestinationsAsync(plan: MigrationPlan): Future[Either[String, Seq[ResolvedMigrationTarget]]] = {
    if (!allocationRuntime.enabled) return Future.successful(resolveMigrationDestinations(plan))
    migrationTypeNames(plan) match {
      case Left(reason) => Future.successful(Left(reason))
      case Right(types) =>
        val request = ResolveAllocationPlacement(java.util.UUID.randomUUID().toString, allocationEpoch.get,
          allocationControllerId, plan.shardId, plan.sourceNode.toString, plan.targetNode.toString, types.toVector)
        allocationRuntime.request(request).map {
          case e: AllocationPlacementResolved if e.requestId == request.requestId && e.epoch == request.epoch =>
            Right(e.targets.map(t => ResolvedMigrationTarget(t.typeName, t.target, t.source)))
          case e: AllocationRejected => Left(e.reason)
          case _ => Left("uncorrelated allocation resolution reply")
        }.recover { case scala.util.control.NonFatal(error) => Left(error.getMessage) }
    }
  }

  protected def startMigrationPlacementMonitoring(plan: MigrationPlan, targets: Seq[ResolvedMigrationTarget]): String = {
    val batchId = activeWave.get.batchId
    val attempt = PlacementAttempt(java.util.UUID.randomUUID().toString, batchId, plan, targets)
    placementAttempts(plan.shardId) = attempt
    self ! PlacementPoll(plan.shardId, batchId, attempt.id)
    attempt.id
  }

  private def pollMigrationPlacement(event: PlacementPoll): Unit =
    placementAttempts.get(event.shardId).filter(a => a.id == event.attemptId && a.batchId == event.batchId &&
      a.installed && !a.inFlight && !a.verified).foreach { attempt =>
      attempt.poll = None
      attempt.inFlight = true
      attempt.round += 1L
      val round = attempt.round
      val shardId = event.shardId
      implicit val timeout: Timeout = Timeout(5.seconds)
      def query(region: ActorRef): Future[Option[ShardRegion.CurrentShardRegionState]] =
        (region ? ShardRegion.GetShardRegionState).mapTo[ShardRegion.CurrentShardRegionState]
          .map(state => Option(state)).recover { case scala.util.control.NonFatal(_) => None }
      val observations = attempt.targets.map { target =>
        val source = query(target.sourceRegion)
        val destination = query(target.region)
        source.zip(destination).map {
          case (Some(from), Some(to)) =>
            from.failed.isEmpty && to.failed.isEmpty && !from.shards.exists(_.shardId == shardId) &&
              to.shards.exists(_.shardId == shardId)
          case _ => false
        }.recover { case scala.util.control.NonFatal(_) => false }
      }
      Future.sequence(observations).map { results =>
        PlacementObserved(shardId, event.batchId, event.attemptId, round, results.nonEmpty && results.forall(identity))
      }.pipeTo(self)
    }

  private def handlePlacementObserved(event: PlacementObserved): Unit =
    placementAttempts.get(event.shardId).filter(a => a.id == event.attemptId && a.batchId == event.batchId &&
      a.inFlight && a.round == event.round).foreach { attempt =>
      attempt.inFlight = false
      attempt.verified = event.verified
      if (event.verified) {
        if (allocationRuntime.enabled) activeWave.filter(_.batchId == attempt.batchId).foreach { wave =>
          attempt.targets.foreach { target =>
            wave.entityIdsByShard(event.shardId).filter(id => SpatialShardIdRegistry.getEntityClassName(id)
              .exists(name => StringUtil.getModelClassName(name) == target.typeName)).foreach { id =>
              target.region ! ShardRegion.StartEntity(id)
            }
          }
        }
        maybeCompletePlacement(event.shardId)
      }
      else {
        logDebug(s"Placement is not yet verified for '${event.shardId}' in round ${event.round}")
        attempt.poll = Some(context.system.scheduler.scheduleOnce(500.milliseconds, self,
          PlacementPoll(event.shardId, event.batchId, event.attemptId)))
      }
    }

  private def maybeCompletePlacement(shardId: String): Unit =
    placementAttempts.get(shardId).filter(a => a.verified && !a.completionQueued).foreach { attempt =>
      activeWave.filter(w => w.batchId == attempt.batchId &&
        w.entityIdsByShard(shardId).intersect(w.pendingRestoreEntityAcks.toSet).isEmpty).foreach { _ =>
        attempt.completionQueued = true
        if (allocationRuntime.enabled) {
          val request = RetireAllocationIntent(java.util.UUID.randomUUID().toString, allocationEpoch.get,
            allocationControllerId, attempt.batchId, attempt.id, shardId)
          allocationRuntime.request(request).map {
            case e: AllocationIntentRetired if e.requestId == request.requestId && e.epoch == request.epoch &&
                e.batchId == request.batchId && e.attemptId == request.attemptId =>
              IntentAcknowledged(shardId, request.batchId, request.attemptId, true, None)
            case e: AllocationRejected => IntentAcknowledged(shardId, request.batchId, request.attemptId, true, Some(e.reason))
            case _ => IntentAcknowledged(shardId, request.batchId, request.attemptId, true, Some("uncorrelated allocation retirement reply"))
          }.recover { case scala.util.control.NonFatal(error) =>
            IntentAcknowledged(shardId, request.batchId, request.attemptId, true, Some(error.getMessage))
          }.pipeTo(self)
        } else self ! completionEvent(attempt)
      }
    }

  private def clearPlacementAttempts(): Unit = {
    placementAttempts.values.foreach(_.poll.foreach(_.cancel()))
    placementAttempts.clear()
  }

  private def completionEvent(attempt: PlacementAttempt): MigrationCompleteEvent =
    MigrationCompleteEvent(attempt.plan.shardId, durationMs = (System.nanoTime() - attempt.startedAt) / 1000000L,
      batchId = attempt.batchId, attemptId = attempt.id)

  private def handleIntentAcknowledged(event: IntentAcknowledged): Unit =
    placementAttempts.get(event.shardId).filter(a => a.id == event.attemptId && a.batchId == event.batchId).foreach { attempt =>
      event.failure match {
        case Some(reason) => failMigrationPreparation(reason)
        case None if event.retiring && attempt.completionQueued => self ! completionEvent(attempt)
        case None if !event.retiring && !attempt.installed =>
          attempt.installed = true
          self ! PlacementPoll(event.shardId, event.batchId, event.attemptId)
        case _ => ()
      }
    }

  override def postStop(): Unit = {
    clearPlacementAttempts()
    super.postStop()
  }

  /** Classifies an entity as Static or Dynamic based on its actor `classType` (not the id string,
    * which is now an opaque `Long`).
    *
    *   - Static: `node`, `link`, `rail_link`, `traffic_signal`
    *   - Dynamic: everything else (`car`, `bus`, `bicycle`, `motorcycle`, `person`, `subway`)
    *
    * The classification determines whether the shard is eligible for migration.
    */
  private def classifyEntityType(entity: SpatialEntity): ShardTypeEnum = {
    val ct = entity.entityClassType
    if (ct != null && ct.nonEmpty) {
      val lower = ct.toLowerCase
      if (
        lower.contains("node") || lower.contains("link") ||
        lower.contains("trafficsignal") || lower.contains("signal")
      ) {
        ShardTypeEnum.Static
      } else {
        ShardTypeEnum.Dynamic
      }
    } else {
      classifyEntityTypeFromId(entity.spatialEntityId)
    }
  }

  /** Legacy fallback: classifies from the `htcaid:{type};{id}` convention. */
  private def classifyEntityTypeFromId(entityId: String): ShardTypeEnum = {
    val entityType = entityId.toLowerCase
    if (
      entityType.contains(":node;") || entityType.contains(":link;") ||
      entityType.contains(":traffic_signal;") || entityType.contains(":signal;")
    ) {
      ShardTypeEnum.Static
    } else {
      ShardTypeEnum.Dynamic
    }
  }

  /** Gets the shard type for a shard ID. Defaults to Dynamic for unknown shards. */
  def getShardType(shardId: String): ShardTypeEnum =
    shardTypes.getOrElse(shardId, ShardTypeEnum.Dynamic)

  /** Checks whether a shard is eligible for migration. */
  def isMigratable(shardId: String): Boolean =
    getShardType(shardId) != ShardTypeEnum.Static
}

/** Response event sent back from LoadBalanceManager to the creation pipeline with the logical shard
  * assignment for an entity.
  */
case class ShardAssignmentResponse(
  entityId: String,
  shardId: String
)

object LoadBalanceManager {
  private case class AllocationBound(epoch: String)
  private case class AllocationStartupFailed(reason: String)
  private case class DestinationsResolved(batchId: String,
    results: Vector[(String, Either[String, Seq[ResolvedMigrationTarget]])])
  private case class IntentAcknowledged(shardId: String, batchId: String, attemptId: String,
    retiring: Boolean, failure: Option[String])
  case class ResolvedMigrationTarget(typeName: String, region: ActorRef, sourceRegion: ActorRef)
  private[loadbalance] case class PlacementPoll(shardId: String, batchId: String, attemptId: String)
  private[loadbalance] case class PlacementObserved(shardId: String, batchId: String, attemptId: String,
    round: Long, verified: Boolean)
  def props(
    timeManager: ActorRef,
    simulationManager: ActorRef,
    strategyType: LoadBalanceStrategyEnum,
    worldBounds: SpatialBounds,
    strategyConfig: StrategyConfig
  ): Props =
    Props(
      classOf[LoadBalanceManager],
      timeManager,
      simulationManager,
      strategyType,
      worldBounds,
      strategyConfig
    )
}
