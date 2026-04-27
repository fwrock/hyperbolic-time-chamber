package org.interscity.htc
package core.actor.manager.loadbalance

import core.actor.manager.BaseManager
import core.actor.manager.loadbalance.allocation.{ LoadBalanceShardAllocator, ShardAllocatorRegistry, SpatialShardIdRegistry }
import core.actor.manager.loadbalance.migration.{ MigrationRequestResult, MigrationStateStoreRegistry, MigrationWindowSubscriber, ShardMigrationCoordinator }
import core.actor.manager.loadbalance.strategy.{ BalancingStrategy, StrategyConfig, StrategyFactory }
import core.entity.control.loadbalance._
import core.entity.event.EntityEnvelopeEvent
import core.entity.event.control.loadbalance._
import core.entity.event.control.loadbalance.ShardRegionMetricsCollectedEvent
import core.entity.event.control.migration.{ MigrationRestoredAckEvent, MigrationWindowAckEvent, MigrationWindowCloseEvent, MigrationWindowOpenEvent, RegisterMigrationBatchEvent }
import core.entity.state.DefaultState
import core.enumeration.{ LoadBalanceStrategyEnum, ShardTypeEnum }
import core.util.ManagerConstantsUtil.LOAD_BALANCE_MANAGER_ACTOR_NAME
import core.util.IdUtil

import org.apache.pekko.actor.{ ActorRef, Cancellable, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.ClusterEvent.{ CurrentClusterState, MemberEvent, MemberRemoved, MemberUp, UnreachableMember }
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Publish
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ShardRegion }
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
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

  /** Lazy DistributedPubSub mediator reference for broadcasting window events. */
  private lazy val mediatorRef: ActorRef = DistributedPubSub(context.system).mediator

  // ── Per-wave migration tracking state ────────────────────────────────────

  /** Per-wave migration tracking state. */
  private case class MigrationWaveState(
    batchId: String,
    plans: Seq[MigrationPlan],
    allEntityIds: Set[String],
    pendingWindowOpenNodeAcks: mutable.Set[String],
    pendingRestoreEntityAcks: mutable.Set[String],
    pendingWindowCloseNodeAcks: mutable.Set[String]
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
    ShardAllocatorRegistry.register(shardAllocator)

    // Schedule periodic rebalancing
    if (strategy.isDefined) {
      scheduleRebalancing()
      scheduleMetricsCollection()
    }

    // Initialize proxies
    selfProxy = createSingletonProxy(LOAD_BALANCE_MANAGER_ACTOR_NAME)
    timeManagerProxy = timeManager

    // Notify SimulationManager that we're ready
    simulationManager ! LoadBalanceReadyEvent(getSelfProxy)

    logInfo(
      s"LoadBalanceManager ready. Strategy: ${strategy.map(_.name).getOrElse("disabled")}, " +
        s"Cluster nodes: ${cluster.state.members.count(_.status == MemberStatus.Up)}"
    )
  }

  // ── Message Handling ───────────────────────────────────────────────────────

  override def handleEvent: Receive = {
    // Load balance events
    case event: UpdateLoadMetricsEvent            => handleUpdateMetrics(event)
    case event: RegisterSpatialEntityEvent        => handleRegisterEntity(event)
    case event: RegisterSpatialEntitiesBatchEvent => handleBatchRegisterEntities(event)
    case event: RequestMigrationEvent             => handleMigrationRequest(event)
    case event: MigrationCompleteEvent            => handleMigrationComplete(event)
    case event: TriggerRebalanceEvent             => handleTriggerRebalance(event)
    case _: CollectAndFeedMetricsEvent            => handleCollectAndFeedMetrics()
    case event: ShardRegionMetricsCollectedEvent  => handleShardRegionMetricsCollected(event)

    // TimeManager coordination events
    case event: MigrationSafeEvent => handleMigrationSafe(event)

    case cmd: TriggerWindowOpenEvent      => handleTriggerWindowOpen(cmd)
    case event: MigrationWindowAckEvent   => handleWindowAck(event)
    case event: MigrationRestoredAckEvent => handleRestoredAck(event)

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

    case _: StopSimulationEvent => handleStopSimulation()
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

        val entityType = classifyEntityType(event.entity.spatialEntityId)
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

            val entityType = classifyEntityType(entity.spatialEntityId)
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
        logInfo(
          s"Migration queued for execution: shard '${event.plan.shardId}' " +
            s"from ${event.plan.sourceNode} to ${event.plan.targetNode} " +
            s"(reason: ${event.plan.reason})"
        )
        pendingMigrationPlans.enqueue(event.plan)
        requestTimeManagerPause()

      case MigrationRequestResult.Queued(pos) =>
        logInfo(s"Migration queued at position $pos: shard '${event.plan.shardId}'")

      case MigrationRequestResult.AlreadyMigrating =>
        logWarn(s"Shard '${event.plan.shardId}' is already being migrated")

      case MigrationRequestResult.Rejected(reason) =>
        logWarn(s"Migration rejected for shard '${event.plan.shardId}': $reason")
    }
  }

  /** Requests the TimeManager to pause at a safe tick boundary.
    *
    * Only sends one request at a time — if we're already waiting, additional migration plans just
    * queue up and will be executed once the TimeManager signals safety.
    */
  private def requestTimeManagerPause(): Unit =
    if (!awaitingMigrationPause && pendingMigrationPlans.nonEmpty) {
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
    *   1. Register batch with SnapshotManager 2. Notify entities to serialize state to SM
    *      (PrepareForMigrationEvent with batchId) 3. Schedule window-open broadcast after 200ms
    *      (let snapshots reach SM) 4. Wait for open ACKs from all nodes → then trigger Pekko
    *      rebalance per plan 5. Wait for entity restore ACKs → then broadcast window-close 6. Wait
    *      for close ACKs → then notify TimeManager to resume
    */
  private def handleMigrationSafe(event: MigrationSafeEvent): Unit = {
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
    val allEntityIds = dynamicPlans
      .flatMap(
        p => SpatialShardIdRegistry.getEntitiesInShard(p.shardId).toSeq
      )
      .map(IdUtil.format) // Format to match shard routing keys used by TM
      .toSet

    val clusterNodeAddrs: mutable.Set[String] = mutable.Set.empty ++
      cluster.state.members
        .filter(_.status == MemberStatus.Up)
        .map(_.address.toString)

    MigrationStateStoreRegistry.getSnapshotManager.foreach {
      smRef =>
        smRef ! RegisterMigrationBatchEvent(batchId, allEntityIds, getSelfProxy)
    }

    dynamicPlans.foreach {
      plan =>
        notifyEntitiesForMigrationWithBatch(plan.shardId, plan.targetNode.toString, batchId)
    }

    activeWave = Some(
      MigrationWaveState(
        batchId = batchId,
        plans = dynamicPlans.toSeq,
        allEntityIds = allEntityIds,
        pendingWindowOpenNodeAcks = clusterNodeAddrs,
        pendingRestoreEntityAcks = mutable.Set.empty ++ allEntityIds,
        pendingWindowCloseNodeAcks = mutable.Set.empty
      )
    )

    val windowOpenDelayMs =
      try
        context.system.settings.config
          .getInt("htc.load-balance-manager.migration.window-open-delay-ms")
      catch { case _: Exception => 200 }
    context.system.scheduler.scheduleOnce(windowOpenDelayMs.milliseconds) {
      getSelfProxy ! TriggerWindowOpenEvent(batchId, allEntityIds)
    }

    logInfo(
      s"Migration wave '$batchId': ${dynamicPlans.size} dynamic plan(s), " +
        s"${allEntityIds.size} entities, ${clusterNodeAddrs.size} cluster nodes"
    )
  }

  /** Broadcasts MigrationWindowOpenEvent to all nodes via DistributedPubSub. Called 200ms after
    * entity notification to allow snapshots to reach SM first.
    */
  private def handleTriggerWindowOpen(cmd: TriggerWindowOpenEvent): Unit =
    activeWave.filter(_.batchId == cmd.batchId) match {
      case None =>
        logWarn(s"TriggerWindowOpenEvent for unknown batch '${cmd.batchId}' — ignoring")
      case Some(wave) =>
        logInfo(
          s"Broadcasting MigrationWindowOpenEvent for batch '${wave.batchId}' " +
            s"(${cmd.entityIds.size} entities, ${wave.pendingWindowOpenNodeAcks.size} nodes)"
        )
        mediatorRef ! Publish(
          MigrationWindowSubscriber.TOPIC,
          MigrationWindowOpenEvent(
            batchId = wave.batchId,
            entityIds = cmd.entityIds,
            lbmRef = getSelfProxy
          )
        )
    }

  /** Handles a MigrationWindowAckEvent from a cluster node subscriber.
    *
    * Open ACKs: count down. When all nodes ACKed → trigger Pekko rebalance per plan. Close ACKs:
    * count down. When all nodes ACKed → notify TimeManager to resume.
    */
  private def handleWindowAck(event: MigrationWindowAckEvent): Unit =
    activeWave.filter(_.batchId == event.batchId) match {
      case None =>
        logWarn(
          s"MigrationWindowAckEvent for unknown batch '${event.batchId}' (phase=${event.phase}) — ignoring"
        )
      case Some(wave) =>
        event.phase match {
          case MigrationWindowSubscriber.PHASE_OPEN =>
            wave.pendingWindowOpenNodeAcks -= event.nodeAddress
            logDebug(
              s"Window open ACK from '${event.nodeAddress}' " +
                s"(remaining: ${wave.pendingWindowOpenNodeAcks.size})"
            )
            if (wave.pendingWindowOpenNodeAcks.isEmpty) {
              logInfo(
                s"All nodes ACKed window open for batch '${wave.batchId}'. " +
                  s"Triggering Pekko shard rebalance for ${wave.plans.size} plans."
              )
              wave.plans.foreach(triggerPekkoHandoff)
            }

          case MigrationWindowSubscriber.PHASE_CLOSE =>
            wave.pendingWindowCloseNodeAcks -= event.nodeAddress
            logDebug(
              s"Window close ACK from '${event.nodeAddress}' " +
                s"(remaining: ${wave.pendingWindowCloseNodeAcks.size})"
            )
            if (wave.pendingWindowCloseNodeAcks.isEmpty) {
              logInfo(
                s"All nodes ACKed window close for batch '${wave.batchId}'. " +
                  s"Notifying TimeManager to resume."
              )
              activeWave = None
              timeManagerProxy ! MigrationCompleteNotifyEvent()
            }

          case other =>
            logWarn(s"Unknown migration window ACK phase: '$other'")
        }
    }

  /** Handles a MigrationRestoredAckEvent from a fully-restored entity on the target node.
    *
    * Counts down the pending restore set. When all entities in the wave have reported back,
    * broadcasts MigrationWindowCloseEvent to end the distributed flag period.
    */
  private def handleRestoredAck(event: MigrationRestoredAckEvent): Unit =
    activeWave.filter(_.batchId == event.batchId) match {
      case None =>
        logDebug(
          s"MigrationRestoredAckEvent from '${event.entityId}' for batch '${event.batchId}' — " +
            s"no active wave (already closed or not a migration entity)"
        )
      case Some(wave) =>
        wave.pendingRestoreEntityAcks -= event.entityId
        logDebug(
          s"Entity '${event.entityId}' restored. " +
            s"Batch '${event.batchId}': ${wave.pendingRestoreEntityAcks.size} entities remaining."
        )
        if (wave.pendingRestoreEntityAcks.isEmpty) {
          logInfo(
            s"All ${wave.allEntityIds.size} entities restored for batch '${wave.batchId}'. " +
              s"Broadcasting MigrationWindowCloseEvent."
          )
          val clusterNodeAddrs: Set[String] = cluster.state.members
            .filter(_.status == MemberStatus.Up)
            .map(_.address.toString)
          wave.pendingWindowCloseNodeAcks ++= clusterNodeAddrs
          mediatorRef ! Publish(
            MigrationWindowSubscriber.TOPIC,
            MigrationWindowCloseEvent(batchId = wave.batchId, lbmRef = getSelfProxy)
          )
        }
    }

  /** Handles migration completion (Pekko shard hand-off reported done by the monitor).
    *
    * If an active migration wave is in progress, the TimeManager notification is deferred until all
    * entities have been restored (tracked via MigrationRestoredAckEvent) and the distributed window
    * is closed. In that case, do NOT notify TM here.
    *
    * If there is no active wave (static-only migrations), notify TM when all shard hand-offs are
    * complete.
    */
  private def handleMigrationComplete(event: MigrationCompleteEvent): Unit = {
    val nextPlan = migrationCoordinator.completeMigration(event.shardId, event.success)

    if (event.success) {
      logInfo(s"Pekko shard hand-off complete: '${event.shardId}' (${event.durationMs}ms)")
    } else {
      logWarn(s"Pekko shard hand-off reported failure: '${event.shardId}'")
    }

    nextPlan.foreach {
      plan =>
        getSelfProxy ! RequestMigrationEvent(plan)
    }

    val stats = migrationCoordinator.getStats
    if (stats.activeMigrations == 0 && pendingMigrationPlans.isEmpty && activeWave.isEmpty) {
      logInfo("All shard migrations complete (no wave active). Notifying TimeManager to resume.")
      timeManagerProxy ! MigrationCompleteNotifyEvent()
    }

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

    ShardAllocatorRegistry.clear()
    SpatialShardIdRegistry.clear()
    shardAllocator.clearAll()

    rebalanceScheduler.foreach(_.cancel())
    metricsScheduler.foreach(_.cancel())

    migrationCoordinator.abortAll()

    if (awaitingMigrationPause || activeWave.isDefined) {
      timeManagerProxy ! MigrationCompleteNotifyEvent()
      awaitingMigrationPause = false
      activeWave = None
    }

    strategy.foreach(_.shutdown())

    strategy.foreach {
      s =>
        logInfo(s"Final stats: ${s.getStats}")
    }

    selfDestruct()
  }

  /** Sends [[PrepareForMigrationEvent]] to all entities in a shard, requesting them to serialize
    * their state to the migration store before hand-off.
    *
    * This uses the shard region to route the event to each entity. Since we may not have an
    * explicit list of entity IDs per shard, we broadcast via the shard region using the entity IDs
    * tracked in the allocator's shard→entity mapping.
    *
    * @param shardId
    *   The shard being migrated
    * @param targetNode
    *   The target node address (for logging in the event)
    */
  private def notifyEntitiesForMigration(shardId: String, targetNode: String): Unit = {
    val event = PrepareForMigrationEvent(shardId = shardId, targetNode = targetNode)

    val entityIds = SpatialShardIdRegistry.getEntitiesInShard(shardId)

    if (entityIds.nonEmpty) {
      logInfo(
        s"Notifying ${entityIds.size} entities in shard '$shardId' to save state before migration"
      )
      entityIds.foreach {
        eid =>
          try {
            val shardRegionName = SpatialShardIdRegistry.getEntityClassName(eid)
            shardRegionName.foreach {
              className =>
                val region = ClusterSharding(context.system).shardRegion(className)
                region ! EntityEnvelopeEvent(eid, event)
            }
          } catch {
            case e: Exception =>
              logWarn(s"Failed to notify entity '$eid' for migration: ${e.getMessage}")
          }
      }
    } else {
      logDebug(
        s"No entity IDs tracked for shard '$shardId' — entities will save state on DestructEvent"
      )
    }
  }

  /** Notifies entities in a shard to save their state to the SnapshotManager (migration window).
    *
    * Same as [[notifyEntitiesForMigration]] but includes `batchId` and `lbmRef` in the
    * PrepareForMigrationEvent so entities can:
    *   1. Send [[core.entity.event.control.migration.SaveMigrationSnapshotEvent]] to SM 2. Later
    *      send [[core.entity.event.control.migration.MigrationRestoredAckEvent]] to LBM
    */
  private def notifyEntitiesForMigrationWithBatch(
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
            val shardRegionName = SpatialShardIdRegistry.getEntityClassName(eid)
            shardRegionName.foreach {
              className =>
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
            }
          } catch {
            case e: Exception =>
              logWarn(
                s"Failed to notify entity '$eid' for migration (batch=$batchId): ${e.getMessage}"
              )
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
    * partition and should not move.
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
      getSelfProxy ! MigrationCompleteEvent(
        shardId = plan.shardId,
        success = true,
        durationMs = 0
      )
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
  private def triggerPekkoHandoff(plan: MigrationPlan): Unit = {
    logInfo(
      s"Triggering Pekko hand-off: shard '${plan.shardId}' (type=${shardTypes
          .getOrElse(plan.shardId, ShardTypeEnum.Dynamic)}) → ${plan.targetNode}"
    )

    val targetRegion = shardAllocator.getRegionIndex.find {
      case (_, shards) => shards.nonEmpty
    }
      .map(_._1)

    targetRegion match {
      case Some(region) =>
        shardAllocator.requestRebalance(plan.shardId, region)
        logInfo(
          s"Shard '${plan.shardId}' marked for rebalance in allocator. " +
            s"Pekko ShardCoordinator will pick it up on next rebalance cycle."
        )
        val startTime = System.currentTimeMillis()
        monitorMigrationCompletion(plan.shardId, startTime, attempt = 1)

      case None =>
        logWarn(
          s"No target region found for shard '${plan.shardId}'. " +
            s"Region index is empty (cluster may still be forming). Reporting failure."
        )
        getSelfProxy ! MigrationCompleteEvent(
          shardId = plan.shardId,
          success = false,
          durationMs = 0
        )
    }
  }

  /** Periodically checks whether a shard migration has completed.
    *
    * Pekko's shard hand-off is async: the shard is passivated on the source node and re-created on
    * the target. We check the allocator's region index to see if the shard has moved. After
    * maxAttempts, we report completion anyway (Pekko handles delivery guarantees).
    */
  private def monitorMigrationCompletion(
    shardId: String,
    startTime: Long,
    attempt: Int,
    maxAttempts: Int = 20,
    checkIntervalMs: Long = 500
  ): Unit =
    context.system.scheduler.scheduleOnce(checkIntervalMs.milliseconds) {
      val migrationStillPending = shardAllocator.getPendingRebalances.contains(shardId)
      val duration = System.currentTimeMillis() - startTime

      if (!migrationStillPending || attempt >= maxAttempts) {
        val success = !migrationStillPending
        if (!success) {
          logWarn(
            s"Migration of shard '$shardId' did not complete after $maxAttempts attempts (${duration}ms). " +
              s"Reporting success anyway — Pekko may still be processing."
          )
        }
        getSelfProxy ! MigrationCompleteEvent(
          shardId = shardId,
          success = true,
          durationMs = duration
        )
      } else {
        monitorMigrationCompletion(shardId, startTime, attempt + 1, maxAttempts, checkIntervalMs)
      }
    }

  /** Classifies an entity as Static or Dynamic based on its actor ID convention.
    *
    * Entity IDs follow the pattern `htcaid:type;id` where type indicates the actor class:
    *   - Static: `node`, `link`, `traffic_signal`
    *   - Dynamic: `car`, `bus`, `bicycle`, `motorcycle`, `person`, `subway`
    *
    * The classification determines whether the shard is eligible for migration.
    */
  private def classifyEntityType(entityId: String): ShardTypeEnum = {
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
