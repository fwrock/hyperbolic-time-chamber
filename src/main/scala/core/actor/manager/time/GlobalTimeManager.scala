package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.{GlobalTimeManager, TimeManagerBase}
import core.entity.control.{LocalTimeManagerTickInfo, ScheduledActors}
import core.entity.event.control.execution.TimeManagerRegisterEvent
import core.entity.event.control.load.{ProgressiveLoadingCompleteEvent, RegisterProgressiveLoadManagerEvent, TickWindowReady, TickWindowRequest}
import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.entity.state.DefaultState
import core.metrics.MetricsServer
import core.types.Tick
import core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, POOL_TIME_MANAGER_ACTOR_NAME}

import org.apache.pekko.actor.{ActorRef, Props, Terminated}
import org.apache.pekko.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{LocalTimeReportEvent, RegisterActorEvent, StartSimulationTimeEvent, UpdateGlobalTimeEvent}
import core.entity.event.control.loadbalance.{MigrationCompleteNotifyEvent, MigrationSafeEvent, RequestMigrationPauseEvent}

import scala.collection.mutable

/** Global Time Manager that coordinates all local time managers. This manager acts as a central
  * coordinator, synchronizing time across distributed local time managers and ensuring consistent
  * simulation progress.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  */
class GlobalTimeManager(
  val simulationDuration: Tick,
  val extendSimulationIfPendingEventsAfterEnd: Boolean,
  val simulationManager: ActorRef
) extends TimeManagerBase(
      timeManager = null,
      actorId = GLOBAL_TIME_MANAGER_ACTOR_NAME
    ) {

  private var selfProxy: ActorRef = null
  private var timeManagersPool: ActorRef = _
  private val localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerTickInfo] = mutable.Map()
  @volatile private var isTerminated = false

  /** When true, the GlobalTimeManager will not advance to the next tick.
    * Set by RequestMigrationPauseEvent from LoadBalanceManager, cleared by MigrationCompleteNotifyEvent.
    */
  private var migrationPauseRequested: Boolean = false

  /** Reference to the LoadBalanceManager that requested the pause, for sending MigrationSafeEvent. */
  private var migrationRequester: ActorRef = _

  // Progressive loading coordination
  private var progressiveLoadManager: ActorRef = _
  private var progressiveLoadingEnabled = false
  private var progressiveLoadedUpToTick: Tick = Long.MaxValue
  private var maxLookAheadTicks: Tick = 10_000L
  private var waitingForProgressiveLoad = false
  private var pendingNextTick: Option[Tick] = None
  private var progressiveLoadingComplete = false

  // Tick duration measurement — tracks wall-clock time between global tick broadcasts
  private var lastTickBroadcastNanos: Long = 0L

  // Adaptive pre-fetch: tracks the actual tick range of the last loaded window.
  // Used to compute a dynamic pre-fetch threshold instead of a static value.
  // When the remaining buffer drops below PREFETCH_RATIO * lastWindowTickRange,
  // a new TickWindowRequest is sent proactively.
  private var lastWindowTickRange: Tick = 1000L
  private val PREFETCH_RATIO = 0.4
  private val MIN_PREFETCH_BUFFER: Tick = 100

  // Holds the StartSimulationTimeEvent while waiting for the initial progressive window
  private var pendingStartEvent: Option[StartSimulationTimeEvent] = None
  private var waitingForInitialWindow = false

  override def onStart(): Unit =
    createTimeManagersPool()

  private def createTimeManagersPool(): Unit = {
    // Read from config, with sensible defaults for cluster distribution.
    // Multiple LocalTMs per node spread the mailbox load: each TM processes
    // FinishEvent/ScheduleEvent sequentially, so N TMs per node = N parallel
    // mailbox processors for the pod's actors.
    val config = context.system.settings.config
    val totalInstances = config.getInt("htc.time-manager.total-instances")
    val maxInstancesPerNode = config.getInt("htc.time-manager.max-instances-per-node")
    logInfo(
      s"Creating LocalTM pool: totalInstances=$totalInstances, " +
        s"maxInstancesPerNode=$maxInstancesPerNode"
    )
    timeManagersPool = context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(0),
        ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(
        LocalDiscreteEventTimeManager.props(
          simulationDuration,
          simulationManager,
          Some(getSelfProxy)
        )
      ),
      name = POOL_TIME_MANAGER_ACTOR_NAME
    )
    // Don't register the router - let each instance register itself
    simulationManager ! TimeManagerRegisterEvent(actorRef = timeManagersPool)
  }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case schedule: ScheduleEvent         => scheduleEvent(schedule)
    case timeManagerRegisterEvent: TimeManagerRegisterEvent =>
      registerTimeManager(timeManagerRegisterEvent)
    case localTimeReport: LocalTimeReportEvent =>
      handleLocalTimeReport(localTimeReport)
    case migrationPause: RequestMigrationPauseEvent =>
      handleMigrationPauseRequest(migrationPause)
    case _: MigrationCompleteNotifyEvent =>
      handleMigrationComplete()
    case event: RegisterProgressiveLoadManagerEvent =>
      handleRegisterProgressiveLoadManager(event)
    case event: TickWindowReady =>
      handleTickWindowReady(event)
    case event: ProgressiveLoadingCompleteEvent =>
      onProgressiveLoadingComplete()
    case Terminated(ref) =>
      handleTimeManagerTerminated(ref)
    case event => super.handleEvent(event)
  }

  private def handleRegisterProgressiveLoadManager(event: RegisterProgressiveLoadManagerEvent): Unit = {
    progressiveLoadManager = event.progressiveLoadManager
    progressiveLoadingEnabled = true
    progressiveLoadedUpToTick = -1L
    maxLookAheadTicks = event.lookAheadTicks
    lastWindowTickRange = event.lookAheadTicks // Initial estimate until first real window
    logInfo(s"Progressive load manager registered with maxLookAhead=${event.lookAheadTicks}")
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Global TimeManager started at tick ${event.startTick}")
    event.data.foreach(
      data => startTime = data.startTime
    )
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false

    // If progressive loading is enabled, we MUST wait for the initial window
    // to be loaded before starting the simulation. Otherwise actors scheduled
    // at early ticks won't exist yet when the local TMs try to activate them.
    if (progressiveLoadingEnabled && !progressiveLoadingComplete) {
      logInfo(
        s"Progressive loading enabled — holding simulation start until initial window " +
          s"from tick $initialTick is loaded (PLM will determine adaptive window size)"
      )
      pendingStartEvent = Some(event)
      waitingForInitialWindow = true
      requestProgressiveLoad(initialTick)
      return  // Do NOT notify local managers yet
    }

    notifyLocalManagers(event)
  }

  protected def registerActor(event: RegisterActorEvent): Unit = {
    // Global time manager doesn't register actors directly
    // Actors are registered with local time managers
  }

  protected def scheduleEvent(event: ScheduleEvent): Unit =
    // Forward schedule requests to the appropriate local time manager
    timeManagersPool ! event

  protected def finishEvent(event: FinishEvent): Unit = {
    // Finish events are handled by local time managers
  }

  override protected def pauseSimulation(): Unit = {
    super.pauseSimulation()
    notifyLocalManagers(org.htc.protobuf.core.entity.event.control.execution.PauseSimulationEvent())
  }

  override protected def resumeSimulation(): Unit =
    if (isPaused) {
      isPaused = false
      self ! SpontaneousEvent(tick = localTickOffset, actorRef = self)
      notifyLocalManagers(
        org.htc.protobuf.core.entity.event.control.execution.ResumeSimulationEvent()
      )
    }

  override protected def stopSimulation(): Unit = {
    super.stopSimulation()
    terminateSimulation()
  }

  private def registerTimeManager(event: TimeManagerRegisterEvent): Unit = {
    val isNew = !localTimeManagers.contains(event.actorRef)
    localTimeManagers.put(
      event.actorRef,
      LocalTimeManagerTickInfo(tick = localTickOffset)
    )
    if (isNew) {
      // Watch for termination so we can remove stale TMs from crashed nodes
      context.watch(event.actorRef)
      logInfo(
        s"Registered LocalTimeManager: ${event.actorRef.path.name} " +
          s"on ${event.actorRef.path.address} - Total registered: ${localTimeManagers.size}"
      )
    }
  }

  /**
   * Handle termination of a LocalTimeManager (e.g. node crash, shard rebalancing).
   * Removes the dead TM from the map so the synchronization barrier doesn't wait forever.
   */
  private def handleTimeManagerTerminated(ref: ActorRef): Unit = {
    if (localTimeManagers.contains(ref)) {
      localTimeManagers.remove(ref)
      logWarn(
        s"LocalTimeManager terminated: ${ref.path.name} on ${ref.path.address}. " +
          s"Remaining: ${localTimeManagers.size}"
      )
      // Check if all remaining TMs have reported (the dead one was the only straggler)
      if (localTimeManagers.nonEmpty && localTimeManagers.values.forall(_.isProcessed)) {
        logInfo("All remaining managers reported after TM termination, advancing tick")
        calculateAndBroadcastNextGlobalTick()
      }
    }
  }

  private def handleLocalTimeReport(report: LocalTimeReportEvent): Unit = {
    val manager = sender()

    if (!localTimeManagers.contains(manager)) {
      logWarn(s"Received report from unregistered manager: ${manager.path}")
      return
    }

    localTimeManagers.update(
      manager,
      LocalTimeManagerTickInfo(
        tick = report.tick,
        hasSchedule = report.hasScheduled,
        isProcessed = true
      )
    )

    val processedCount = localTimeManagers.values.count(_.isProcessed)
    val totalCount = localTimeManagers.size

    if (localTimeManagers.values.forall(_.isProcessed)) {
      logDebug(s"All $totalCount managers reported, calculating next tick")
      // If migration pause is pending, signal the requester that it's now safe
      if (migrationPauseRequested && migrationRequester != null) {
        logInfo(s"All local managers reported. Signaling migration safe at tick $localTickOffset")
        migrationRequester ! MigrationSafeEvent(currentTick = localTickOffset)
        // Do NOT advance — calculateAndBroadcastNextGlobalTick will hold
      }
      calculateAndBroadcastNextGlobalTick()
    } else {
      logDebug(s"Waiting for more reports: $processedCount/$totalCount processed")
    }
  }

  private def calculateAndBroadcastNextGlobalTick(): Unit = {
    // If migration pause is active, do NOT advance to the next tick.
    // The TimeManager waits until LoadBalanceManager sends MigrationCompleteNotifyEvent.
    if (migrationPauseRequested) {
      logInfo(s"Migration pause active — holding at tick $localTickOffset until migration completes")
      return
    }

    val totalManagers = localTimeManagers.size
    val scheduled = localTimeManagers.values.filter(_.hasSchedule)
    val scheduledCount = scheduled.size

    if (scheduled.isEmpty) {
      // Before terminating, check if progressive loading still has actors to spawn.
      // This handles the case where all currently loaded actors are passive (e.g. parked
      // vehicles waiting for Person actors to activate them), but future progressive windows
      // contain actors with scheduled events (persons, etc.). Without this guard the
      // simulation would terminate prematurely — persons would never be created because
      // the TM shuts down before reaching their startTick.
      if (progressiveLoadingEnabled && !progressiveLoadingComplete && !waitingForProgressiveLoad) {
        val nextLoadTick = progressiveLoadedUpToTick + 1
        logInfo(
          s"No scheduled events but progressive loading not complete " +
            s"(loadedUpTo=$progressiveLoadedUpToTick). Requesting next window from tick $nextLoadTick."
        )
        MetricsServer.tmWaitingForProgressive.set(1)
        waitingForProgressiveLoad = true
        pendingNextTick = Some(nextLoadTick)
        requestProgressiveLoad(nextLoadTick)
        return
      }
      logInfo("No more scheduled events across local time managers. Terminating simulation")
      terminateSimulation()
      return
    }

    val nextTick = scheduled.map(_.tick).min

    // ── Prometheus: tick metrics ──
    MetricsServer.simulationTicks.inc()
    MetricsServer.currentTick.set(nextTick.toDouble)
    if (simulationDuration > 0) {
      MetricsServer.simulationProgress.set(
        Math.min(1.0, (nextTick - initialTick).toDouble / simulationDuration.toDouble)
      )
    }
    // Measure tick cycle duration (wall-clock between consecutive broadcasts)
    val nowNanos = System.nanoTime()
    if (lastTickBroadcastNanos > 0) {
      val durationSec = (nowNanos - lastTickBroadcastNanos) / 1e9
      MetricsServer.tickDuration.observe(durationSec)
    }
    lastTickBroadcastNanos = nowNanos

    localTickOffset = nextTick
    tickOffset = nextTick - initialTick

    // Check if simulation should terminate by configured duration.
    // If extension is enabled, allow simulation to continue beyond duration
    // while there are still scheduled events (vehicles finishing their trips).
    if (
      !extendSimulationIfPendingEventsAfterEnd && localTickOffset - initialTick >= simulationDuration
    ) {
      logInfo(s"Simulation reached configured duration ($simulationDuration ticks). Terminating.")
      terminateSimulation()
      return
    }

    // Check if progressive loading needs more actors before advancing
    if (progressiveLoadingEnabled && !progressiveLoadingComplete && nextTick > progressiveLoadedUpToTick) {
      logInfo(
        s"Waiting for progressive load: nextTick=$nextTick > loadedUpTo=$progressiveLoadedUpToTick"
      )
      MetricsServer.tmWaitingForProgressive.set(1)
      waitingForProgressiveLoad = true
      pendingNextTick = Some(nextTick)
      requestProgressiveLoad(nextTick)
      return
    }

    // Proactively request next window if getting close to the boundary.
    // Threshold adapts based on actual window sizes: dense windows produce smaller
    // ranges, so the prefetch triggers earlier (in ticks) to compensate.
    if (progressiveLoadingEnabled && !progressiveLoadingComplete && !waitingForProgressiveLoad) {
      val remainingBuffer = progressiveLoadedUpToTick - nextTick
      val prefetchThreshold = Math.max(MIN_PREFETCH_BUFFER, (lastWindowTickRange * PREFETCH_RATIO).toLong)
      if (remainingBuffer < prefetchThreshold) {
        logDebug(
          s"Proactive prefetch: remainingBuffer=$remainingBuffer < threshold=$prefetchThreshold " +
            s"(lastWindowRange=$lastWindowTickRange)"
        )
        requestProgressiveLoad(nextTick)
      }
    }

    localTimeManagers.keys.foreach {
      timeManager =>
        localTimeManagers.update(
          timeManager,
          LocalTimeManagerTickInfo(tick = nextTick)
        )
    }

    notifyLocalManagers(UpdateGlobalTimeEvent(localTickOffset))
  }

  private def notifyLocalManagers(event: Any): Unit =
    // Broadcast to all routees in the pool
    timeManagersPool ! org.apache.pekko.routing.Broadcast(event)

  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    // Handled by local time managers
  }

  protected def advanceToNextTick(): Unit = {
    // Coordination happens through local time manager reports
  }

  protected def nextTick: Option[Tick] =
    if (localTickOffset - initialTick >= simulationDuration) {
      None
    } else {
      Some(localTickOffset + 1)
    }

  /** Handles a migration pause request from LoadBalanceManager.
    *
    * The GlobalTimeManager will finish processing all current tick's spontaneous events
    * (via LocalTimeManagers) but will NOT advance to the next tick. Once all LocalTimeManagers
    * have reported completion of the current tick, it sends MigrationSafeEvent back to the
    * LoadBalanceManager with the current tick, confirming it's safe to migrate.
    */
  private def handleMigrationPauseRequest(event: RequestMigrationPauseEvent): Unit = {
    logInfo(
      s"Migration pause requested for shards ${event.shardIds} at tick $localTickOffset. " +
        s"Will pause after current tick completes."
    )
    migrationPauseRequested = true
    migrationRequester = event.requester

    // If all local managers have already reported (i.e., we're between ticks), signal immediately
    if (localTimeManagers.values.forall(_.isProcessed)) {
      logInfo(s"All local managers idle. Signaling migration safe at tick $localTickOffset")
      migrationRequester ! MigrationSafeEvent(currentTick = localTickOffset)
    }
    // Otherwise, the pause will take effect in calculateAndBroadcastNextGlobalTick
    // when all local managers report — it will hold there and then send MigrationSafeEvent
  }

  /** Handles migration completion notification from LoadBalanceManager.
    * Resumes normal tick advancement.
    */
  private def handleMigrationComplete(): Unit = {
    logInfo(s"Migration complete. Resuming tick advancement from tick $localTickOffset")
    migrationPauseRequested = false
    migrationRequester = null
    // Resume by recalculating the next global tick
    calculateAndBroadcastNextGlobalTick()
  }

  /**
   * Request progressive loading of actors starting from currentTick.
   * Sends a large max horizon; the PLM decides the actual window size
   * based on actor density (adaptive window sizing).
   */
  private def requestProgressiveLoad(currentTick: Tick): Unit = {
    if (progressiveLoadManager != null) {
      val horizonTick = currentTick + maxLookAheadTicks
      progressiveLoadManager ! TickWindowRequest(
        currentTick = currentTick,
        horizonTick = horizonTick
      )
    }
  }

  /**
   * Handle response from ProgressiveLoadDataManager confirming actors are ready.
   */
  private def handleTickWindowReady(event: TickWindowReady): Unit = {
    // Track the actual range this window covered for adaptive prefetch threshold
    val previousLoadedUpTo = progressiveLoadedUpToTick
    progressiveLoadedUpToTick = event.readyUpToTick
    if (previousLoadedUpTo >= 0 && event.readyUpToTick > previousLoadedUpTo) {
      lastWindowTickRange = event.readyUpToTick - previousLoadedUpTo
    }

    // CASE 1: We were waiting for the initial window before starting simulation
    if (waitingForInitialWindow) {
      waitingForInitialWindow = false
      lastWindowTickRange = Math.max(1, event.readyUpToTick - initialTick)
      logInfo(
        s"Initial progressive window loaded up to tick ${event.readyUpToTick} " +
          s"(${event.actorsCreated} actors, range=$lastWindowTickRange ticks). Starting simulation now."
      )
      pendingStartEvent.foreach { startEvent =>
        pendingStartEvent = None
        notifyLocalManagers(startEvent)
      }
      return
    }

    logDebug(
      s"Progressive load ready up to tick ${event.readyUpToTick}, " +
        s"${event.actorsCreated} actors created in this window"
    )

    // CASE 2: We were waiting mid-simulation for the next window
    if (waitingForProgressiveLoad) {
      waitingForProgressiveLoad = false
      MetricsServer.tmWaitingForProgressive.set(0)
      pendingNextTick.foreach { tick =>
        if (tick <= progressiveLoadedUpToTick) {
          // If a migration pause is active, do NOT advance. The pending tick is preserved in
          // pendingNextTick; handleMigrationComplete → calculateAndBroadcastNextGlobalTick will
          // pick up from here once migration completes.
          if (migrationPauseRequested) {
            logInfo(
              s"Progressive window ready at tick $tick but migration pause active — " +
                s"holding until migration completes"
            )
          } else {
            logDebug(s"Resuming simulation after progressive load, advancing to tick $tick")
            localTimeManagers.keys.foreach { timeManager =>
              localTimeManagers.update(
                timeManager,
                LocalTimeManagerTickInfo(tick = tick)
              )
            }
            notifyLocalManagers(UpdateGlobalTimeEvent(tick))
          }
        }
      }
    }
  }

  /**
   * Called when the ProgressiveLoadDataManager signals all progressive sources are exhausted.
   */
  def onProgressiveLoadingComplete(): Unit = {
    progressiveLoadingComplete = true
    progressiveLoadedUpToTick = Long.MaxValue
    logInfo("Progressive loading complete - no more tick window checks needed")
  }

  private def terminateSimulation(): Unit = synchronized {
    if (!isTerminated) {
      isTerminated = true
      printSimulationDuration()
      logInfo("Global simulation terminated")
      notifyLocalManagers(
        org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent()
      )
    }
  }

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }
}

object GlobalTimeManager {
  def props(
    simulationDuration: Tick,
    extendSimulationIfPendingEventsAfterEnd: Boolean,
    simulationManager: ActorRef
  ): Props =
    Props(
      classOf[GlobalTimeManager],
      simulationDuration,
      extendSimulationIfPendingEventsAfterEnd,
      simulationManager
    )
}
