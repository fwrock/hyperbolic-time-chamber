package org.interscity.htc
package core.actor.manager.loadbalance

import core.actor.manager.BaseManager
import core.actor.manager.loadbalance.allocation.{ LoadBalanceShardAllocator, ShardAllocatorRegistry, SpatialShardIdRegistry }
import core.actor.manager.loadbalance.allocation.*
import core.actor.manager.loadbalance.migration.{ MigrationRequestResult, MigrationStateStoreRegistry, MigrationWindowSubscriber, ShardMigrationCoordinator }
import core.actor.manager.loadbalance.strategy.{ BalancingStrategy, StrategyConfig, StrategyFactory }
import core.actor.manager.loadbalance.spatial.{ RegionLoadObservation, ShardLoadObservation }
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

import org.apache.pekko.actor.{ ActorRef, Address, AddressFromURIString, Cancellable, Props }
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
  *      TimeManager: RequestMigrationPauseEvent 3. TimeManager closes its causal batch and freezes live owners 4. TimeManager → LoadBalanceManager:
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
  private var requestedMigrationShards = Set.empty[String]
  private var migrationPauseRequestId = ""

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
  private var balancingStarted = false
  private var migrationClosed = false
  private var metricsSequence = 0L
  private var metricsInFlight = false
  private var placementGeneration = 0L
  private lazy val allocationRuntime = AllocationRuntime(context.system)
  private val allocationControllerId = java.util.UUID.randomUUID().toString
  private var allocationEpoch: Option[String] = None
  private val startupMigrations = mutable.Queue.empty[MigrationPlan]
  private lazy val spatialDirectory = SpatialDirectoryRuntime(context.system)
  private val pendingSpatialPublications = mutable.Map.empty[String, (ActorRef, Any)]
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
    liveMembers: Map[String, core.runtime.conservative.ConservativeMigrationMember] = Map.empty,
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
    if (allocationRuntime.enabled) spatialDirectory.observe(self)

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
    case _: org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent =>
      balancingStarted = true
      self ! CollectAndFeedMetricsEvent()
    case core.runtime.conservative.ConservativeMigrationDeclined(requestId)
      if awaitingMigrationPause && activeWave.isEmpty && requestId == migrationPauseRequestId =>
      logInfo(s"Migration request '$requestId' declined: conservative runtime is finalizing")
      migrationClosed = true
      balancingStarted = false
      awaitingMigrationPause = false
      requestedMigrationShards = Set.empty
      pendingMigrationPlans.clear()
      startupMigrations.clear()
      migrationCoordinator.abortAll()
      rebalanceScheduler.foreach(_.cancel())
      metricsScheduler.foreach(_.cancel())
    case _: core.runtime.conservative.ConservativeMigrationDeclined => ()
    case AllocationBound(epoch) =>
      allocationEpoch = Some(epoch)
      simulationManager ! LoadBalanceReadyEvent(getSelfProxy)
      startupMigrations.dequeueAll(_ => true).foreach(plan => self ! RequestMigrationEvent(plan))
    case AllocationStartupFailed(reason) => failMigrationPreparation(reason)
    case e: AllocationAuthorityFailure if e.controllerId == allocationControllerId => failMigrationPreparation(e.reason)
    case _: AllocationAuthorityFailure => ()
    case e: SpatialDirectoryFailed => failMigrationPreparation(e.reason)
    case e: SpatialPublicationCompleted =>
      pendingSpatialPublications.remove(e.requestId).foreach { case (replyTo, response) =>
        e.result match {
          case Right(_) => replyTo ! response
          case Left(reason) => failMigrationPreparation(reason)
        }
      }
    case e: MetricsObserved => handleMetricsObserved(e)
    case e: DestinationsResolved => handleDestinationsResolved(e)
    case e: IntentAcknowledged => handleIntentAcknowledged(e)
    // Load balance events
    case event: UpdateLoadMetricsEvent            => handleUpdateMetrics(event)
    case event: RegisterSpatialEntityEvent        => handleSpatialRegistration(handleRegisterEntity(event))
    case event: RegisterSpatialEntitiesBatchEvent => handleSpatialRegistration(handleBatchRegisterEntities(event))
    case _: RequestMigrationEvent if migrationClosed => ()
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

  private def handleSpatialRegistration(registration: => Unit): Unit =
    try {
      require(!allocationRuntime.enabled || activeWave.isEmpty,
        "spatial enrollment during migration is unsupported")
      registration
    }
    catch {
      // Restarting would erase already published assignments while creation is still in progress.
      case error: IllegalArgumentException =>
        failMigrationPreparation(s"Spatial registration rejected: ${error.getMessage}")
    }

  private def publishSpatialAssignments(assignments: Map[String, String], entities: Seq[SpatialEntity],
    response: Any, replyTo: ActorRef): Unit = {
    if (!allocationRuntime.enabled) { replyTo ! response; return }
    val rows = entities.map { entity =>
      require(Option(entity.entityClassType).exists(_.trim.nonEmpty), "distributed spatial assignment requires actor class metadata")
      val (x, y) = entity.position
      SpatialAssignment(entity.spatialEntityId, assignments(entity.spatialEntityId),
        StringUtil.getModelClassName(entity.entityClassType.trim), x, y)
    }.toVector.sortBy(_.entityId)
    val requestId = java.util.UUID.randomUUID().toString
    pendingSpatialPublications(requestId) = replyTo -> response
    spatialDirectory.publish(requestId, rows).map(e => SpatialPublicationCompleted(requestId, Right(e.revision)))
      .recover { case scala.util.control.NonFatal(error) => SpatialPublicationCompleted(requestId, Left(error.getMessage)) }
      .pipeTo(self)
  }

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

        val entityType = SpatialEntityClassification.classify(event.entity)
        shardTypes.get(shardId) match {
          case Some(existing) if existing != entityType =>
            shardTypes.put(shardId, ShardTypeEnum.Mixed)
          case None =>
            shardTypes.put(shardId, entityType)
          case _ =>
        }

        publishSpatialAssignments(Map(event.entity.spatialEntityId -> shardId), Seq(event.entity),
          ShardAssignmentResponse(event.entity.spatialEntityId, shardId), sender())
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

            val entityType = SpatialEntityClassification.classify(entity)
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

        val response = BatchShardAssignmentResponse(
          assignments = assignments.toMap,
          positions = positions.toMap,
          batchId = event.batchId,
          chunkIndex = event.chunkIndex
        )
        publishSpatialAssignments(assignments.toMap, event.entities, response, sender())

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
        timeManagerProxy ! MigrationCompleteNotifyEvent(migrationPauseRequestId)
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
      placementGeneration += 1L
      val shardIds = pendingMigrationPlans.map(_.shardId).toSet
      requestedMigrationShards = shardIds
      migrationPauseRequestId = java.util.UUID.randomUUID().toString
      logInfo(s"Requesting TimeManager to pause for migration of shards: $shardIds")
      timeManagerProxy ! RequestMigrationPauseEvent(
        shardIds = shardIds,
        requester = self,
        targetNodes = pendingMigrationPlans.map(p => p.shardId -> p.targetNode.toString).toMap,
        requestId = migrationPauseRequestId
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
    if (!awaitingMigrationPause || activeWave.isDefined ||
      (event.liveMembers.nonEmpty && event.requestId != migrationPauseRequestId)) {
      logWarn(s"Ignoring unsolicited migration-safe notification at tick ${event.currentTick}")
      return
    }
    logInfo(
      s"TimeManager paused at tick ${event.currentTick}. Executing ${pendingMigrationPlans.size} pending migrations."
    )
    awaitingMigrationPause = false

    if (pendingMigrationPlans.isEmpty) {
      logWarn("Received MigrationSafeEvent but no pending migrations. Resuming TimeManager.")
      timeManagerProxy ! MigrationCompleteNotifyEvent(migrationPauseRequestId)
      return
    }

    val plansToExecute = pendingMigrationPlans.dequeueAll(
      p => requestedMigrationShards.contains(p.shardId)
    )
    requestedMigrationShards = Set.empty
    val live = event.liveMembers.getOrElse(Vector.empty)
    val liveById = live.map(m => m.id.id.toString -> m).toMap
    if (liveById.size != live.size || live.exists(m =>
      !SpatialShardIdRegistry.getEntityClassName(m.id.id.toString).contains(m.id.model))) {
      failMigrationPreparation("invalid or duplicate authoritative live migration cohort")
      return
    }
    val liveIdsByShard = plansToExecute.map { plan =>
      plan.shardId -> (if (event.liveMembers.nonEmpty) live.filter(m =>
        SpatialShardIdRegistry.getShardId(m.id.id.toString).contains(plan.shardId)).map(_.id.id.toString).toSet
      else SpatialShardIdRegistry.getEntitiesInShard(plan.shardId))
    }.toMap
    if (event.liveMembers.nonEmpty && plansToExecute.exists(plan => liveIdsByShard(plan.shardId).exists { id =>
      val ref = liveById(id).ref
      val address = if (ref.path.address.hasLocalScope) cluster.selfAddress else ref.path.address
      address != plan.sourceNode
    })) {
      failMigrationPreparation("live migration cohort does not occupy the planned source")
      return
    }

    val staticPlans = plansToExecute.filter {
      p =>
        shardTypes.getOrElse(p.shardId, ShardTypeEnum.Dynamic) != ShardTypeEnum.Dynamic
    }
    val dynamicPlans = plansToExecute.filter {
      p =>
        shardTypes.getOrElse(p.shardId, ShardTypeEnum.Dynamic) == ShardTypeEnum.Dynamic &&
          (event.liveMembers.isEmpty || liveIdsByShard(p.shardId).nonEmpty)
    }
    staticPlans.foreach(executeMigration)
    val retiredPlans = plansToExecute.filter(p => !staticPlans.contains(p) && !dynamicPlans.contains(p))
    retiredPlans.foreach(skipMigration)

    if (dynamicPlans.isEmpty) {
      if (staticPlans.isEmpty && retiredPlans.isEmpty) timeManagerProxy ! MigrationCompleteNotifyEvent(migrationPauseRequestId)
      return
    }

    val batchId = java.util.UUID.randomUUID().toString.take(12)
    if (pendingSpatialPublications.nonEmpty) {
      failMigrationPreparation("migration reached a safe boundary during spatial directory publication")
      return
    }
    val entityIdsByShard = dynamicPlans.map(p => p.shardId ->
      liveIdsByShard(p.shardId).map(IdUtil.format)).toMap
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
        pendingPlacementShards = mutable.Set.empty ++ dynamicPlans.map(_.shardId),
        liveMembers = liveById
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
    logError(s"[SIMULATION-FAILED] Load balancing failed; stopping the simulation: $reason")
    migrationFailed = true
    abortPreparedSources()
    activeWave = None
    pendingMigrationPlans.clear()
    startupMigrations.clear()
    pendingSpatialPublications.clear()
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
      if (wave.liveMembers.nonEmpty) {
        wave.liveMembers.values.foreach(_.ref ! AbortMigrationPreparationEvent(wave.batchId))
      } else {
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
              if (!awaitingMigrationPause) self ! CollectAndFeedMetricsEvent()
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
    if (!attempt.get.skipped) strategy.foreach(_.recordShardLocation(event.shardId, attempt.get.plan.targetNode))
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
    if ((!allocationRuntime.enabled || balancingStarted) && activeWave.isEmpty && !awaitingMigrationPause) strategy.foreach {
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
    pendingSpatialPublications.clear()

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
    val captured = activeWave.filter(_.batchId == batchId)
    val entityIds = captured.flatMap(_.entityIdsByShard.get(shardId)).getOrElse(
      SpatialShardIdRegistry.getEntitiesInShard(shardId).map(IdUtil.format)).map(_.toString)
    if (entityIds.nonEmpty) {
      logInfo(
        s"Notifying ${entityIds.size} entities in shard '$shardId' to save state " +
          s"(batch='$batchId')"
      )
      entityIds.foreach {
        eid =>
          try {
            val request = PrepareForMigrationEvent(shardId, targetNode, batchId, getSelfProxy)
            captured.flatMap(_.liveMembers.get(eid)) match {
              case Some(member) => member.ref ! request
              case None =>
                val className = SpatialShardIdRegistry.getEntityClassName(eid).getOrElse(
                  throw new IllegalStateException(s"No actor class registered for '$eid'"))
                val region = ClusterSharding(context.system).shardRegion(className)
                region ! EntityEnvelopeEvent(eid, request)
            }
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

  private def scheduleMetricsCollection(): Unit = {
    val interval = config.getDouble("htc.load-balance-manager.metrics.interval-seconds").seconds
    metricsScheduler = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = interval,
        delay = interval
      ) {
        () =>
          getSelfProxy ! CollectAndFeedMetricsEvent()
      }
    )
  }

  private def handleCollectAndFeedMetrics(): Unit = {
    if (strategy.isEmpty || metricsInFlight || activeWave.nonEmpty || awaitingMigrationPause ||
      (allocationRuntime.enabled && (!balancingStarted || allocationEpoch.isEmpty))) return
    metricsInFlight = true
    metricsSequence += 1L
    val sequence = metricsSequence
    val generation = placementGeneration
    val nodes = cluster.state.members.filter(_.status == MemberStatus.Up).map(_.address)
    implicit val timeout: Timeout = Timeout(5.seconds)
    val regions = if (allocationRuntime.enabled) {
      val request = GetRegisteredAllocationRegions(java.util.UUID.randomUUID().toString, allocationEpoch.get, allocationControllerId)
      allocationRuntime.request(request).map {
        case e: RegisteredAllocationRegions if e.requestId == request.requestId && e.epoch == request.epoch =>
          require(e.regions.nonEmpty && e.regions.groupBy(_.typeName).values.forall(rows => rows.map(r => AddressFromURIString(r.address)).toSet == nodes),
            "registered region cohort is incomplete")
          e.regions
        case e: AllocationRejected => throw new IllegalStateException(e.reason)
        case _ => throw new IllegalStateException("uncorrelated region discovery")
      }
    } else {
      val sharding = ClusterSharding(context.system)
      Future.successful(sharding.shardTypeNames.toVector.sorted.map(name =>
        RegisteredAllocationRegion(name, sharding.shardRegion(name), cluster.selfAddress.toString)))
    }
    regions.flatMap(rows => Future.sequence(rows.map { row =>
      (row.region ? ShardRegion.GetShardRegionState).mapTo[ShardRegion.CurrentShardRegionState].map { state =>
        require(state.failed.isEmpty, s"incomplete shard observation for ${row.typeName}/${row.address}")
        RegionLoadObservation(row.typeName, AddressFromURIString(row.address), state)
      }
    })).map(rows => MetricsObserved(sequence, generation, nodes, rows, None))
      .recover { case scala.util.control.NonFatal(error) => MetricsObserved(sequence, generation, nodes, Vector.empty,
        Some(error.getMessage)) }.pipeTo(self)
  }

  private def handleMetricsObserved(event: MetricsObserved): Unit = {
    if (event.sequence != metricsSequence || !metricsInFlight) return
    metricsInFlight = false
    if (event.generation != placementGeneration || activeWave.nonEmpty || awaitingMigrationPause ||
      event.nodes != cluster.state.members.filter(_.status == MemberStatus.Up).map(_.address)) {
      if (activeWave.isEmpty && !awaitingMigrationPause) self ! CollectAndFeedMetricsEvent()
      return
    }
    event.failure match {
      case Some(reason) => logWarn(s"Discarding incomplete load observation ${event.sequence}: $reason")
      case None =>
        val snapshot = try ShardLoadObservation.collect(event.rows, event.nodes, System.nanoTime())
        catch { case error: IllegalArgumentException =>
          logWarn(s"Discarding load observation ${event.sequence}: ${error.getMessage}")
          return
        }
        val metrics = snapshot.metrics
        val locations = snapshot.locations
        strategy.foreach(_.replaceLoadSnapshot(metrics, locations, event.nodes))
        val loads = event.nodes.toVector.sortBy(_.toString).map { node =>
          s"$node=${metrics.filter(m => locations(m.shardId) == node).map(_.totalWeight).sum}"
        }.mkString(",")
        logInfo(s"[LOAD-BALANCE-METRICS] round=${event.sequence} nodes=${event.nodes.size} shards=${metrics.size} " +
          s"live_entities=${metrics.map(_.entityCount).sum} weighting=live-entities node_loads=[$loads]")
    }
  }

  private def handleShardRegionMetricsCollected(event: ShardRegionMetricsCollectedEvent): Unit = ()

  /** Execute the actual shard migration via Pekko cluster sharding.
    *
    * Uses the [[LoadBalanceShardAllocator]] to influence Pekko's shard coordination:
    *   1. Check shard type — only Dynamic shards can be migrated 2. Find the target region
    *      ActorRef for the target node address 3. Update the allocator's desired allocation map 4.
    *      Mark the shard for rebalance, which Pekko picks up on the next coordinator tick 5.
    *      Monitor completion via a periodic check
    *
    * Static shards (Links, Nodes, TrafficSignals) are skipped because they anchor the spatial
    * partition and must not move.
    *
    * NOTE: For dynamic shards use [[triggerPekkoHandoff]] after the migration window is open. This
    * method is kept for static shard handling.
    */
  private def executeMigration(plan: MigrationPlan): Unit = {
    // Check shard type — Static shards should not be migrated
    val shardType = shardTypes.getOrElse(plan.shardId, ShardTypeEnum.Dynamic)
    if (shardType != ShardTypeEnum.Dynamic) {
      logInfo(
        s"Skipping migration of static shard '${plan.shardId}' (infrastructure actors are pinned)"
      )
      skipMigration(plan)
      return
    }

    triggerPekkoHandoff(plan)
  }

  private def skipMigration(plan: MigrationPlan): Unit = {
    val attempt = PlacementAttempt(java.util.UUID.randomUUID().toString, "", plan, Seq.empty,
      skipped = true, verified = true, completionQueued = true)
    placementAttempts(plan.shardId) = attempt
    self ! MigrationCompleteEvent(plan.shardId, attemptId = attempt.id)
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
        plan.targetNode.toString, targets.map(t => AllocationTarget(t.typeName, t.sourceRegion, t.region)).toVector,
        liveSourceVerified = activeWave.exists(_.liveMembers.nonEmpty))
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
    if (typeNames.exists(name => SpatialEntityClassification.classify(
      SpatialEntityData("", 0.0, 0.0, entityClassType = name)) == ShardTypeEnum.Static))
      return Left(s"Shard '${plan.shardId}' contains pinned infrastructure; mixed infrastructure migration is unsupported")
    Right(typeNames)
  }

  private def resolveMigrationDestinationsAsync(plan: MigrationPlan): Future[Either[String, Seq[ResolvedMigrationTarget]]] = {
    if (!allocationRuntime.enabled) return Future.successful(resolveMigrationDestinations(plan))
    migrationTypeNames(plan) match {
      case Left(reason) => Future.successful(Left(reason))
      case Right(types) =>
        val request = ResolveAllocationPlacement(java.util.UUID.randomUUID().toString, allocationEpoch.get,
          allocationControllerId, plan.shardId, plan.sourceNode.toString, plan.targetNode.toString, types.toVector,
          liveSourceVerified = activeWave.exists(_.liveMembers.nonEmpty))
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
        val needsPhysicalTarget = activeWave.filter(_.liveMembers.nonEmpty).forall { wave =>
          wave.entityIdsByShard(shardId).exists(id => wave.liveMembers.get(id.toString).exists(_.id.model == target.typeName))
        }
        val source = query(target.sourceRegion)
        val destination = query(target.region)
        source.zip(destination).map {
          case (Some(from), Some(to)) =>
            from.failed.isEmpty && to.failed.isEmpty && !from.shards.exists(_.shardId == shardId) &&
              (!needsPhysicalTarget || to.shards.exists(_.shardId == shardId))
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

  /** Gets the shard type for a shard ID. Defaults to Dynamic for unknown shards. */
  def getShardType(shardId: String): ShardTypeEnum =
    shardTypes.getOrElse(shardId, ShardTypeEnum.Dynamic)

  /** Checks whether a shard is eligible for migration. */
  def isMigratable(shardId: String): Boolean =
    getShardType(shardId) == ShardTypeEnum.Dynamic
}

/** Response event sent back from LoadBalanceManager to the creation pipeline with the logical shard
  * assignment for an entity.
  */
case class ShardAssignmentResponse(
  entityId: String,
  shardId: String
)

object LoadBalanceManager {
  private case class MetricsObserved(sequence: Long, generation: Long, nodes: Set[Address],
    rows: Vector[RegionLoadObservation], failure: Option[String])
  private case class SpatialPublicationCompleted(requestId: String, result: Either[String, Long])
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
