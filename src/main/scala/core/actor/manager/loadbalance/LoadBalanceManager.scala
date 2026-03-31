package org.interscity.htc
package core.actor.manager.loadbalance

import core.actor.manager.BaseManager
import core.actor.manager.loadbalance.migration.{ MigrationRequestResult, ShardMigrationCoordinator }
import core.actor.manager.loadbalance.strategy.{ BalancingStrategy, StrategyConfig, StrategyFactory }
import core.entity.control.loadbalance._
import core.entity.event.control.loadbalance._
import core.entity.state.DefaultState
import core.enumeration.LoadBalanceStrategyEnum
import core.util.ManagerConstantsUtil.LOAD_BALANCE_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, Cancellable, Props }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.ClusterEvent.{ MemberEvent, MemberRemoved, MemberUp, UnreachableMember }
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
    case event: UpdateLoadMetricsEvent       => handleUpdateMetrics(event)
    case event: RegisterSpatialEntityEvent   => handleRegisterEntity(event)
    case event: RequestMigrationEvent        => handleMigrationRequest(event)
    case event: MigrationCompleteEvent       => handleMigrationComplete(event)
    case event: TriggerRebalanceEvent        => handleTriggerRebalance(event)

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
    */
  private def handleRegisterEntity(event: RegisterSpatialEntityEvent): Unit = {
    strategy.foreach {
      s =>
        val shardId = s.assignShard(event.entity)
        logDebug(
          s"Entity '${event.entity.spatialEntityId}' mapped to shard '$shardId' (logical assignment)"
        )
        // Reply to the sender with the assigned shard ID so the creation pipeline can use it
        sender() ! ShardAssignmentResponse(event.entity.spatialEntityId, shardId)
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

    // Cancel schedulers
    rebalanceScheduler.foreach(_.cancel())
    metricsScheduler.foreach(_.cancel())

    // Abort active migrations
    migrationCoordinator.abortAll()

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
    * This triggers Pekko's built-in shard hand-off mechanism. The shard will be passivated on the
    * source node and re-created on the target node.
    *
    * TODO: Hook into Pekko's ShardAllocationStrategy to influence allocation decisions.
    * The current implementation uses a completion callback. In production, this should implement
    * a custom ShardAllocationStrategy.rebalance() that returns the shards we want to move,
    * and ShardAllocationStrategy.allocateShard() that directs them to our chosen target nodes.
    */
  private def executeMigration(plan: MigrationPlan): Unit = {
    logInfo(
      s"Executing migration: shard '${plan.shardId}' → ${plan.targetNode}"
    )

    // TODO: Implement actual migration by hooking into Pekko's ShardAllocationStrategy.
    // Current placeholder simulates completion after a brief delay.
    // In production:
    //   1. Update the custom ShardAllocationStrategy's allocation map
    //   2. Trigger ShardCoordinator rebalance via ClusterSharding
    //   3. Wait for shard hand-off to complete (passivate on source, start on target)
    //   4. Report completion
    val startTime = System.currentTimeMillis()
    context.system.scheduler.scheduleOnce(500.milliseconds) {
      val duration = System.currentTimeMillis() - startTime
      getSelfProxy ! MigrationCompleteEvent(
        shardId = plan.shardId,
        success = true,
        durationMs = duration
      )
    }
  }
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
