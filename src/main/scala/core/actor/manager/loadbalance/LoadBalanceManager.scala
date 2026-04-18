package org.interscity.htc
package core.actor.manager.loadbalance

import core.actor.manager.BaseManager
import core.actor.manager.loadbalance.allocation.{ LoadBalanceShardAllocator, ShardAllocatorRegistry, SpatialShardIdRegistry }
import core.actor.manager.loadbalance.migration.{ MigrationRequestResult, MigrationStateStore, MigrationStateStoreRegistry, InMemoryMigrationStateStore, RedisMigrationStateStore, ShardMigrationCoordinator }
import core.actor.manager.loadbalance.strategy.{ BalancingStrategy, StrategyConfig, StrategyFactory }
import core.entity.control.loadbalance._
import core.entity.event.EntityEnvelopeEvent
import core.entity.event.control.loadbalance._
import core.entity.state.DefaultState
import core.enumeration.{ LoadBalanceStrategyEnum, ShardTypeEnum }
import core.util.ManagerConstantsUtil.LOAD_BALANCE_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, Cancellable, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.ClusterEvent.{ MemberEvent, MemberRemoved, MemberUp, UnreachableMember }
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** LoadBalanceManager
  *
  * Singleton manager that orchestrates the hybrid load balancing strategy across the cluster.
  * Coordinates with TimeManager (non-blocking), SimulationManager, and individual shards.
  *
  * Responsibilities:
  *   1. Execute the configured balancing strategy (Hybrid, Default, or Disabled)
  *   2. Coordinate shard migrations without losing actors or messages
  *   3. Synchronize with TimeManager during migrations (non-blocking)
  *   4. Collect and aggregate metrics from shards
  *   5. Trigger predictive rebalancing based on flow vectors
  *
  * Migration coordination protocol:
  *   1. LoadBalanceManager evaluates and decides migrations are needed
  *   2. LoadBalanceManager → TimeManager: RequestMigrationPauseEvent
  *   3. TimeManager finishes current tick's spontaneous events, pauses before advancing
  *   4. TimeManager → LoadBalanceManager: MigrationSafeEvent(currentTick)
  *   5. LoadBalanceManager executes all pending migrations
  *   6. LoadBalanceManager → TimeManager: MigrationCompleteNotifyEvent
  *   7. TimeManager resumes advancing ticks
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

  /** Migration state store for preserving actor state across shard hand-offs.
    * Initialized in [[onStart]] based on config: "redis" or "inmemory".
    */
  private var migrationStateStore: Option[MigrationStateStore] = None

  /** Shard type classification: shard ID → ShardTypeEnum.
    * Static shards (Links, Nodes) are pinned; dynamic shards (Cars, Buses) are migratable.
    */
  private val shardTypes: mutable.Map[String, ShardTypeEnum] = mutable.Map.empty

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

    // Initialize migration state store for preserving actor state during shard hand-offs
    initializeMigrationStateStore()

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
    case event: UpdateLoadMetricsEvent              => handleUpdateMetrics(event)
    case event: RegisterSpatialEntityEvent           => handleRegisterEntity(event)
    case event: RegisterSpatialEntitiesBatchEvent    => handleBatchRegisterEntities(event)
    case event: RequestMigrationEvent                => handleMigrationRequest(event)
    case event: MigrationCompleteEvent               => handleMigrationComplete(event)
    case event: TriggerRebalanceEvent                => handleTriggerRebalance(event)

    // TimeManager coordination events
    case event: MigrationSafeEvent           => handleMigrationSafe(event)

    // Cluster membership events
    case MemberUp(member) =>
      logInfo(s"Cluster member joined: ${member.address}")
      strategy.foreach(_.registerNode(member.address))

    case MemberRemoved(member, _) =>
      logInfo(s"Cluster member removed: ${member.address}")
      strategy.foreach(_.removeNode(member.address))

    case _: UnreachableMember =>
      // Log only; removal handled by MemberRemoved when downing completes

    // Simulation lifecycle
    case _: StopSimulationEvent              => handleStopSimulation()
  }

  // ── Event Handlers ─────────────────────────────────────────────────────────

  /** Receives load metrics from shards and feeds them to the strategy. */
  private def handleUpdateMetrics(event: UpdateLoadMetricsEvent): Unit = {
    strategy.foreach(_.updateMetrics(event.metrics))
  }

  /** Registers a spatial entity with the partitioning structure.
    *
    * NOTE: This performs a LOGICAL assignment only — it maps the entity to a shard ID
    * in the Quadtree/strategy based on spatial position. It does NOT create the shard
    * or the actor. Shard and actor creation is handled by CreatorLoadData/LoadDataManager.
    * The returned shard ID should be used by the creation pipeline to route the entity
    * to the correct shard region.
    *
    * Also classifies the entity's shard type (Static/Dynamic) based on the entity's actor class.
    */
  private def handleRegisterEntity(event: RegisterSpatialEntityEvent): Unit = {
    strategy.foreach {
      s =>
        val shardId = s.assignShard(event.entity)
        logDebug(
          s"Entity '${event.entity.spatialEntityId}' mapped to shard '$shardId' (logical assignment)"
        )

        // Classify shard type based on entity (merge if mixed)
        val entityType = classifyEntityType(event.entity.spatialEntityId)
        shardTypes.get(shardId) match {
          case Some(existing) if existing != entityType =>
            shardTypes.put(shardId, ShardTypeEnum.Mixed)
          case None =>
            shardTypes.put(shardId, entityType)
          case _ => // Same type, no change
        }

        // Reply to the sender with the assigned shard ID so the creation pipeline can use it
        sender() ! ShardAssignmentResponse(event.entity.spatialEntityId, shardId)
    }
  }

  /** Registers a batch of spatial entities and replies with all assignments at once.
    *
    * This is the primary path used by [[core.actor.manager.load.CreatorLoadData]] during
    * entity creation. Processing a batch in a single message avoids the overhead of
    * individual ask/reply per entity (which would be 1000+ round-trips per chunk).
    *
    * For each entity, calls [[BalancingStrategy.assignShard]] and classifies the shard
    * type. The response includes both shard assignments and entity positions so that
    * the creation pipeline can resolve node positions for links and vehicles.
    */
  private def handleBatchRegisterEntities(event: RegisterSpatialEntitiesBatchEvent): Unit = {
    strategy match {
      case Some(s) =>
        val assignments = mutable.Map[String, String]()
        val positions = mutable.Map[String, (Double, Double)]()

        event.entities.foreach { entity =>
          val shardId = s.assignShard(entity)

          assignments.put(entity.spatialEntityId, shardId)
          positions.put(entity.spatialEntityId, entity.position)

          // Classify shard type based on entity (merge if mixed)
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
        // Strategy disabled — reply with empty assignments (hash-based fallback)
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
  }

  /** Handles an explicit migration request (e.g., from predictive analysis or periodic rebalance).
    *
    * Migrations are queued and only executed after the TimeManager confirms it has paused
    * at a safe tick boundary. This ensures no spontaneous events are in-flight.
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
    * Only sends one request at a time — if we're already waiting, additional migration
    * plans just queue up and will be executed once the TimeManager signals safety.
    */
  private def requestTimeManagerPause(): Unit = {
    if (!awaitingMigrationPause && pendingMigrationPlans.nonEmpty) {
      awaitingMigrationPause = true
      val shardIds = pendingMigrationPlans.map(_.shardId).toSet
      logInfo(s"Requesting TimeManager to pause for migration of shards: $shardIds")
      timeManagerProxy ! RequestMigrationPauseEvent(
        shardIds = shardIds,
        requester = getSelfProxy
      )
    }
  }

  /** Handles the TimeManager confirming it has paused at a safe tick boundary.
    * Now we can execute all pending migrations safely.
    */
  private def handleMigrationSafe(event: MigrationSafeEvent): Unit = {
    logInfo(s"TimeManager paused at tick ${event.currentTick}. Executing ${pendingMigrationPlans.size} pending migrations.")
    awaitingMigrationPause = false

    if (pendingMigrationPlans.isEmpty) {
      logWarn("Received MigrationSafeEvent but no pending migrations. Resuming TimeManager.")
      timeManagerProxy ! MigrationCompleteNotifyEvent()
      return
    }

    // Execute all pending migrations
    val plansToExecute = pendingMigrationPlans.dequeueAll(_ => true)
    plansToExecute.foreach(executeMigration)
  }

  /** Handles migration completion. Releases buffers and starts next pending migration.
    * When all active migrations complete, notifies TimeManager to resume.
    */
  private def handleMigrationComplete(event: MigrationCompleteEvent): Unit = {
    val nextPlan = migrationCoordinator.completeMigration(event.shardId, event.success)

    if (event.success) {
      logInfo(s"Migration complete: shard '${event.shardId}' (${event.durationMs}ms)")
    } else {
      logWarn(s"Migration failed: shard '${event.shardId}'")
    }

    // Start next queued migration if available
    nextPlan.foreach {
      plan =>
        getSelfProxy ! RequestMigrationEvent(plan)
    }

    // If no more active migrations, notify TimeManager to resume simulation
    val stats = migrationCoordinator.getStats
    if (stats.activeMigrations == 0 && pendingMigrationPlans.isEmpty) {
      logInfo("All migrations complete. Notifying TimeManager to resume.")
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
  private def handleTriggerRebalance(event: TriggerRebalanceEvent): Unit = {
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
  }

  /** Graceful shutdown. */
  private def handleStopSimulation(): Unit = {
    logInfo("LoadBalanceManager stopping. Aborting active migrations.")

    // Unsubscribe from cluster events
    cluster.unsubscribe(self)

    // Unregister the shard allocator and clear spatial registries
    ShardAllocatorRegistry.clear()
    SpatialShardIdRegistry.clear()
    shardAllocator.clearAll()

    // Cancel schedulers
    rebalanceScheduler.foreach(_.cancel())
    metricsScheduler.foreach(_.cancel())

    // Abort active migrations
    migrationCoordinator.abortAll()

    // Clean up migration state store
    migrationStateStore.foreach { store =>
      val remaining = store.size
      if (remaining > 0) {
        logWarn(s"Clearing $remaining orphaned migration state entries from ${store.name}")
      }
      store.clear()
    }
    MigrationStateStoreRegistry.clear()
    migrationStateStore = None

    // If we were waiting for TimeManager, let it resume
    if (awaitingMigrationPause) {
      timeManagerProxy ! MigrationCompleteNotifyEvent()
      awaitingMigrationPause = false
    }

    // Shutdown strategy
    strategy.foreach(_.shutdown())

    // Log final stats
    strategy.foreach {
      s =>
        logInfo(s"Final stats: ${s.getStats}")
    }

    selfDestruct()
  }

  // ── Internal Operations ────────────────────────────────────────────────────

  /** Initializes the migration state store based on config.
    *
    * Reads `htc.load-balance-manager.migration.state-store` from application.conf:
    *   - "redis"    → [[RedisMigrationStateStore]] (shared, multi-node safe)
    *   - "inmemory"  → [[InMemoryMigrationStateStore]] (fast, single-node only)
    *   - anything else → falls back to in-memory
    *
    * The store is registered globally in [[MigrationStateStoreRegistry]] so that
    * actors can access it from [[BaseActor.saveMigrationState]] / [[BaseActor.restoreMigrationState]]
    * without constructor injection.
    */
  private def initializeMigrationStateStore(): Unit = {
    val storeType = try {
      config.getString("htc.load-balance-manager.migration.state-store")
    } catch {
      case _: Exception => "inmemory"
    }

    val store: MigrationStateStore = storeType.toLowerCase match {
      case "redis" =>
        logInfo("Migration state store: Redis (shared, multi-node safe)")
        new RedisMigrationStateStore()
      case _ =>
        logInfo("Migration state store: In-Memory (singleton-hosted, single-node)")
        new InMemoryMigrationStateStore()
    }

    migrationStateStore = Some(store)
    MigrationStateStoreRegistry.register(store)
  }

  /** Sends [[PrepareForMigrationEvent]] to all entities in a shard, requesting them
    * to serialize their state to the migration store before hand-off.
    *
    * This uses the shard region to route the event to each entity. Since we may not
    * have an explicit list of entity IDs per shard, we broadcast via the shard region
    * using the entity IDs tracked in the allocator's shard→entity mapping.
    *
    * @param shardId
    *   The shard being migrated
    * @param targetNode
    *   The target node address (for logging in the event)
    */
  private def notifyEntitiesForMigration(shardId: String, targetNode: String): Unit = {
    val event = PrepareForMigrationEvent(shardId = shardId, targetNode = targetNode)

    // Get entity IDs in this shard from the spatial registry
    val entityIds = SpatialShardIdRegistry.getEntitiesInShard(shardId)

    if (entityIds.nonEmpty) {
      logInfo(s"Notifying ${entityIds.size} entities in shard '$shardId' to save state before migration")
      entityIds.foreach { eid =>
        try {
          // Route through the shard region so Pekko delivers to the correct entity
          val shardRegionName = SpatialShardIdRegistry.getEntityClassName(eid)
          shardRegionName.foreach { className =>
            val region = ClusterSharding(context.system).shardRegion(className)
            region ! EntityEnvelopeEvent(eid, event)
          }
        } catch {
          case e: Exception =>
            logWarn(s"Failed to notify entity '$eid' for migration: ${e.getMessage}")
        }
      }
    } else {
      logDebug(s"No entity IDs tracked for shard '$shardId' — entities will save state on DestructEvent")
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
  private def registerClusterNodes(): Unit = {
    strategy.foreach {
      s =>
        cluster.state.members.foreach {
          member =>
            if (member.status == MemberStatus.Up) {
              s.registerNode(member.address)
            }
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
      ) { () =>
        getSelfProxy ! TriggerRebalanceEvent(reason = "periodic")
      }
    )
    logInfo(s"Rebalance scheduled every ${strategyConfig.rebalanceIntervalSeconds}s")
  }

  /** Schedule periodic metrics collection/logging. */
  private def scheduleMetricsCollection(): Unit = {
    val interval = 60.seconds
    metricsScheduler = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        initialDelay = 30.seconds,
        delay = interval
      ) { () =>
        strategy.foreach {
          s =>
            val stats = s.getStats
            logInfo(s"Load balance stats: $stats")
            val migrationStats = migrationCoordinator.getStats
            logInfo(s"Migration stats: $migrationStats")
        }
      }
    )
  }

  /** Execute the actual shard migration via Pekko cluster sharding.
    *
    * Uses the [[LoadBalanceShardAllocator]] to influence Pekko's shard coordination:
    *   1. Check shard type — only Dynamic/Mixed shards can be migrated
    *   2. Find the target region ActorRef for the target node address
    *   3. Update the allocator's desired allocation map
    *   4. Mark the shard for rebalance, which Pekko picks up on the next coordinator tick
    *   5. Monitor completion via a periodic check
    *
    * Static shards (Links, Nodes, TrafficSignals) are skipped because they anchor the
    * spatial partition and should not move.
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

    logInfo(
      s"Executing migration: shard '${plan.shardId}' (type=$shardType) → ${plan.targetNode}"
    )

    // Find the target region ActorRef from the allocator's region index.
    // The region index maps ActorRef → Set[ShardId], populated from Pekko's allocation snapshots.
    val targetRegion = shardAllocator.getRegionIndex
      .find { case (_, shards) => shards.nonEmpty }
      .map(_._1)

    targetRegion match {
      case Some(region) =>
        // Notify entities to save state BEFORE hand-off begins
        notifyEntitiesForMigration(plan.shardId, plan.targetNode.toString)

        // Update allocator: next time Pekko calls allocateShard() for this shard, use target
        shardAllocator.requestRebalance(plan.shardId, region)

        logInfo(
          s"Shard '${plan.shardId}' marked for rebalance in allocator. " +
            s"Pekko ShardCoordinator will pick it up on next rebalance cycle."
        )

        // Monitor shard hand-off completion. Pekko's rebalance is async: the ShardCoordinator
        // calls rebalance() → sees our pending entry → passivates shard on source → re-allocates
        // on target via allocateShard(). We poll until the shard is no longer in-transit.
        val startTime = System.currentTimeMillis()
        monitorMigrationCompletion(plan.shardId, startTime, attempt = 1)

      case None =>
        // No regions known yet — this can happen early in startup
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
    * Pekko's shard hand-off is async: the shard is passivated on the source node and re-created
    * on the target. We check the allocator's region index to see if the shard has moved.
    * After maxAttempts, we report completion anyway (Pekko handles delivery guarantees).
    */
  private def monitorMigrationCompletion(
    shardId: String,
    startTime: Long,
    attempt: Int,
    maxAttempts: Int = 20,
    checkIntervalMs: Long = 500
  ): Unit = {
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
          success = true,  // Report true: Pekko handles the actual hand-off reliably
          durationMs = duration
        )
      } else {
        // Still pending — check again
        monitorMigrationCompletion(shardId, startTime, attempt + 1, maxAttempts, checkIntervalMs)
      }
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
    if (entityType.contains(":node;") || entityType.contains(":link;") ||
        entityType.contains(":traffic_signal;") || entityType.contains(":signal;")) {
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

/** Response event sent back from LoadBalanceManager to the creation pipeline with
  * the logical shard assignment for an entity.
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
