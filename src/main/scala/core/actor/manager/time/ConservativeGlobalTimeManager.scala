package org.interscity.htc
package core.actor.manager.time

import core.entity.control.LocalTimeManagerTickInfo
import core.entity.event.control.execution.TimeManagerRegisterEvent
import core.entity.event.control.execution.QueryNextTickEvent
import core.entity.event.control.load.{ProgressiveLoadManagerRegisteredEvent, ProgressiveLoadingCompleteEvent, RegisterProgressiveLoadManagerEvent, TickWindowReady, TickWindowRequest}
import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.types.Tick
import core.util.ManagerConstantsUtil.GLOBAL_TIME_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ActorRef, CoordinatedShutdown, Props, Terminated}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{LocalTimeReportEvent, RegisterActorEvent, StartSimulationTimeEvent, StopSimulationEvent, UpdateGlobalTimeEvent}
import core.entity.event.control.loadbalance.{MigrationCompleteNotifyEvent, MigrationSafeEvent, RequestMigrationPauseEvent}
import core.api.SimulatorSettingsRegistry
import org.interscity.htc.core.metrics.core.{ProgressiveLoadingMetrics, SimulationMetrics, TimeManagerMetrics}
import org.interscity.htc.core.metrics.core.PhaseMetrics

import scala.collection.mutable
import scala.concurrent.duration._

/** Conservative (`SelectiveBarrier`) global time-manager coordinator: an LTM is only told to
  * advance once every LTM registered here has reported the current tick as fully processed. This
  * is today's (and, before `docs/TIME_WARP_DESIGN.md` §2's split, the only) `GlobalTimeManager`
  * behavior — renamed and rebased onto [[GlobalTimeManagerBase]], no behavior change.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  */
class ConservativeGlobalTimeManager(
  simulationDuration: Tick,
  val extendSimulationIfPendingEventsAfterEnd: Boolean,
  simulationManager: ActorRef
) extends GlobalTimeManagerBase(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      actorId = GLOBAL_TIME_MANAGER_ACTOR_NAME
    ) {

  private val localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerTickInfo] = mutable.Map()
  @volatile private var isTerminated = false

  /** When true, the GlobalTimeManager will not advance to the next tick. Set by
    * RequestMigrationPauseEvent from LoadBalanceManager, cleared by MigrationCompleteNotifyEvent.
    */
  private var migrationPauseRequested: Boolean = false

  /** Reference to the LoadBalanceManager that requested the pause, for sending MigrationSafeEvent.
    */
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

  // Grace period for "no scheduled events" detection: when all LTMs report idle, GTM sends
  // one round of QueryNextTickEvent to all LTMs before terminating. This catches in-flight
  // ScheduleEvents (e.g. Car.handleStartTrip arriving just after Person's LTM reported idle).
  private var gracePeriodUsed = false

  // Progressive load timing — tracks wall-clock time per window and blocked wait
  private var progressiveLoadRequestedNanos: Long = 0L
  private var progressiveLoadBlockedNanos: Long = 0L

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

  // Guard: prevents GTM from advancing ticks before StartSimulationTimeEvent is received.
  // During loading/warmup/post-load-registration, all LTMs register actors and report
  // hasScheduled=true. Without this flag, calculateAndBroadcastNextGlobalTick would fire
  // SpontaneousEvents before progressive loading is set up — causing the simulation to
  // run with loadedUpTo=MaxValue and no Person/Car actors ever spawned.
  private var simulationStarted: Boolean = false

  // Periodic progress log: log at INFO every N ticks so the simulation isn't completely silent
  private var metricsLogInterval: Long = 500L
  private var selectiveBarrierLogInterval: Long = 2000L
  private var localReportWaitLogInterval: Long = 2000L

  // GTM stall detector: wall-clock time of the last UpdateGlobalTimeEvent broadcast.
  private var lastGTMBroadcastWall: Long = System.currentTimeMillis()
  private var lastGTMBroadcastTick: Tick = -1L
  private case object GTMStallCheck

  override def preStart(): Unit = {
    super.preStart()
    context.system.scheduler.scheduleWithFixedDelay(
      30.seconds, 30.seconds, self, GTMStallCheck
    )(context.dispatcher)
  }

  override def onStart(): Unit = {
    val config = context.system.settings.config
    metricsLogInterval =
      SimulatorSettingsRegistry.getInt("htc.time-manager.metrics-log-interval", config).toLong
    selectiveBarrierLogInterval =
      SimulatorSettingsRegistry
        .getInt("htc.time-manager.selective-barrier-log-interval", config)
        .toLong
    localReportWaitLogInterval =
      SimulatorSettingsRegistry
        .getInt("htc.time-manager.local-report-wait-log-interval", config)
        .toLong
    createTimeManagersPool()
  }

  override protected def localTimeManagerProps(): Props =
    LocalDiscreteEventTimeManager.props(
      simulationDuration,
      simulationManager,
      Some(getSelfProxy)
    )

  override def handleEvent: Receive = {
    case GTMStallCheck                   => logGTMStallIfNeeded()
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

  private def handleRegisterProgressiveLoadManager(
    event: RegisterProgressiveLoadManagerEvent
  ): Unit = {
    progressiveLoadManager = event.progressiveLoadManager
    progressiveLoadingEnabled = true
    progressiveLoadedUpToTick = -1L
    progressiveLoadingComplete = false
    maxLookAheadTicks = event.lookAheadTicks
    lastWindowTickRange = event.lookAheadTicks
    logInfo(s"Progressive load manager registered with maxLookAhead=${event.lookAheadTicks}")
    event.ackTo.foreach(_ ! ProgressiveLoadManagerRegisteredEvent(event.lookAheadTicks))
  }

  /** Log a stall report when the GTM has not broadcast a new tick for > 25s. */
  private def logGTMStallIfNeeded(): Unit = {
    if (isTerminated || !simulationStarted) return
    val elapsed = System.currentTimeMillis() - lastGTMBroadcastWall
    if (elapsed <= 25_000) return

    val elapsedS = elapsed / 1000
    if (waitingForInitialWindow) {
      logWarn(s"[GTM-STALL] tick=$localTickOffset | elapsed=${elapsedS}s | waiting for initial progressive window")
      return
    }
    if (waitingForProgressiveLoad) {
      logWarn(s"[GTM-STALL] tick=$localTickOffset | elapsed=${elapsedS}s | waiting for progressive load window")
      return
    }
    if (migrationPauseRequested) {
      logWarn(s"[GTM-STALL] tick=$localTickOffset | elapsed=${elapsedS}s | migration pause requested")
      return
    }

    val total  = localTimeManagers.size
    val pending = localTimeManagers.collect {
      case (ref, info) if !info.isProcessed => ref -> info
    }
    if (pending.nonEmpty) {
      val lines = pending.toSeq
        .sortBy(_._1.path.toString)
        .map { case (ref, info) =>
          val node = ref.path.address.hostPort
          s"    ${ref.path.name}@$node  (tick=${info.tick}, hasSchedule=${info.hasSchedule})"
        }
        .mkString("\n")
      logWarn(
        s"[GTM-STALL] tick=$localTickOffset | elapsed=${elapsedS}s | " +
        s"waiting for ${pending.size}/$total LTMs:\n$lines"
      )
    } else {
      logWarn(
        s"[GTM-STALL] tick=$localTickOffset | elapsed=${elapsedS}s | " +
        s"all $total LTMs processed but no broadcast yet (internal guard?)"
      )
    }
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    simulationStarted = true
    logInfo(s"Global TimeManager started at tick ${event.startTick}")
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    SimulationMetrics.configuredDuration.set(simulationDuration.toDouble)

    localTimeManagers.foreach {
      case (ref, info) =>
        localTimeManagers.update(ref, info.copy(isProcessed = false, hasSchedule = false))
    }

    if (progressiveLoadingEnabled && !progressiveLoadingComplete) {
      logInfo(
        s"Progressive loading enabled — holding simulation start until initial window " +
          s"from tick $initialTick is loaded (PLM will determine adaptive window size)"
      )
      pendingStartEvent = Some(event)
      waitingForInitialWindow = true
      logInfo(s"[StartupPhase] progressive_first_window_request: fromTick=$initialTick")
      requestProgressiveLoad(initialTick)
      return
    }

    PhaseMetrics.recordPhaseStart("simulation")
    logInfo("[StartupPhase] simulation_clock_released_no_progressive")
    notifyLocalManagers(event)
    startTime = System.currentTimeMillis()
  }

  protected def registerActor(event: RegisterActorEvent): Unit = {

  }

  protected def scheduleEvent(event: ScheduleEvent): Unit =
    timeManagersPool ! event

  protected def finishEvent(event: FinishEvent): Unit = {
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
      context.watch(event.actorRef)
      logInfo(
        s"Registered LocalTimeManager: ${event.actorRef.path.name} " +
          s"on ${event.actorRef.path.address} - Total registered: ${localTimeManagers.size}"
      )
    }
  }

  /** Handle termination of a LocalTimeManager (e.g. node crash, shard rebalancing). Removes the
    * dead TM from the map so the synchronization barrier doesn't wait forever.
    */
  private def handleTimeManagerTerminated(ref: ActorRef): Unit =
    if (localTimeManagers.contains(ref)) {
      localTimeManagers.remove(ref)
      logWarn(
        s"LocalTimeManager terminated: ${ref.path.name} on ${ref.path.address}. " +
          s"Remaining: ${localTimeManagers.size}"
      )
      if (localTimeManagers.nonEmpty && localTimeManagers.values.forall(_.isProcessed)) {
        logInfo("All remaining managers reported after TM termination, advancing tick")
        calculateAndBroadcastNextGlobalTick()
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
      if (metricsLogInterval > 0 && tickOffset % metricsLogInterval == 0L)
        logDebug(s"All $totalCount managers reported, calculating next tick")
      // If migration pause is pending, signal the requester that it's now safe
      if (migrationPauseRequested && migrationRequester != null) {
        logInfo(s"All local managers reported. Signaling migration safe at tick $localTickOffset")
        migrationRequester ! MigrationSafeEvent(currentTick = localTickOffset)
        // Do NOT advance — calculateAndBroadcastNextGlobalTick will hold
      }
      calculateAndBroadcastNextGlobalTick()
    } else {
      if (localReportWaitLogInterval > 0 && tickOffset % localReportWaitLogInterval == 0L)
        logDebug(s"Waiting for more reports: $processedCount/$totalCount processed")
    }
  }

  private def calculateAndBroadcastNextGlobalTick(): Unit = {
    if (isTerminated) {
      return
    }
    if (!simulationStarted) {
      logDebug(
        "GTM: suppressing premature tick advancement — StartSimulationTimeEvent not yet received"
      )
      return
    }

    if (waitingForInitialWindow) {
      logDebug("Waiting for initial progressive window — suppressing tick advancement")
      return
    }

    if (migrationPauseRequested) {
      logInfo(
        s"Migration pause active — holding at tick $localTickOffset until migration completes"
      )
      return
    }

    val totalManagers = localTimeManagers.size
    val scheduled = localTimeManagers.values.filter(_.hasSchedule)
    val scheduledCount = scheduled.size

    if (scheduled.isEmpty) {
      if (progressiveLoadingEnabled && !progressiveLoadingComplete && !waitingForProgressiveLoad) {
        val nextLoadTick = progressiveLoadedUpToTick + 1
        logInfo(
          s"No scheduled events but progressive loading not complete " +
            s"(loadedUpTo=$progressiveLoadedUpToTick). Requesting next window from tick $nextLoadTick."
        )
        TimeManagerMetrics.tmWaitingForProgressive.set(1)
        waitingForProgressiveLoad = true
        progressiveLoadBlockedNanos = System.nanoTime()
        pendingNextTick = Some(nextLoadTick)
        requestProgressiveLoad(nextLoadTick)
        return
      }
      if (!gracePeriodUsed) {
        gracePeriodUsed = true
        logInfo("No scheduled events: broadcasting QueryNextTickEvent grace-period probe to all LTMs")
        localTimeManagers.keys.foreach { tm =>
          localTimeManagers.update(tm, localTimeManagers(tm).copy(isProcessed = false))
          tm ! QueryNextTickEvent
        }
        return
      }
      logInfo("No more scheduled events across local time managers. Terminating simulation")
      terminateSimulation()
      return
    }

    gracePeriodUsed = false  // Reset whenever we have scheduled work
    val nextTick = scheduled.map(_.tick).min

    SimulationMetrics.simulationTicks.inc()
    SimulationMetrics.currentTick.set(nextTick.toDouble)
    if (simulationDuration > 0) {
      SimulationMetrics.simulationProgress.set(
        Math.min(1.0, (nextTick - initialTick).toDouble / simulationDuration.toDouble)
      )
    }
    val nowNanos = System.nanoTime()
    if (lastTickBroadcastNanos > 0) {
      val durationSec = (nowNanos - lastTickBroadcastNanos) / 1e9
      SimulationMetrics.tickDuration.observe(durationSec)
    }
    lastTickBroadcastNanos = nowNanos

    localTickOffset = nextTick
    tickOffset = nextTick - initialTick

    if (metricsLogInterval > 0 && tickOffset % metricsLogInterval == 0L) {
      logInfo(
        s"[Tick $localTickOffset] Simulation progress: tick=$localTickOffset " +
          s"(+${tickOffset} from start), managers=${localTimeManagers.size}, " +
          s"loadedUpTo=$progressiveLoadedUpToTick"
      )
    }

    if (
      !extendSimulationIfPendingEventsAfterEnd && localTickOffset - initialTick >= simulationDuration
    ) {
      logInfo(s"Simulation reached configured duration ($simulationDuration ticks). Terminating.")
      terminateSimulation()
      return
    }

    if (
      progressiveLoadingEnabled && !progressiveLoadingComplete && nextTick > progressiveLoadedUpToTick
    ) {
      logInfo(
        s"Waiting for progressive load: nextTick=$nextTick > loadedUpTo=$progressiveLoadedUpToTick"
      )
      TimeManagerMetrics.tmWaitingForProgressive.set(1)
      waitingForProgressiveLoad = true
      progressiveLoadBlockedNanos = System.nanoTime()
      pendingNextTick = Some(nextTick)
      requestProgressiveLoad(nextTick)
      return
    }

    if (progressiveLoadingEnabled && !progressiveLoadingComplete && !waitingForProgressiveLoad) {
      val remainingBuffer = progressiveLoadedUpToTick - nextTick
      val prefetchThreshold =
        Math.max(MIN_PREFETCH_BUFFER, (lastWindowTickRange * PREFETCH_RATIO).toLong)
      if (remainingBuffer < prefetchThreshold) {
        logDebug(
          s"Proactive prefetch: remainingBuffer=$remainingBuffer < threshold=$prefetchThreshold " +
            s"(lastWindowRange=$lastWindowTickRange)"
        )
        requestProgressiveLoad(nextTick)
      }
    }

    var activeCount = 0
    localTimeManagers.foreach { case (tm, info) =>
      if (info.hasSchedule && info.tick <= nextTick) {
        localTimeManagers.update(tm, LocalTimeManagerTickInfo(tick = nextTick, isProcessed = false))
        tm ! UpdateGlobalTimeEvent(localTickOffset)
        activeCount += 1
      } else {
        localTimeManagers.update(tm, info.copy(isProcessed = true))
      }
    }
    if (selectiveBarrierLogInterval > 0 && tickOffset % selectiveBarrierLogInterval == 0L)
      logDebug(
        s"[SelectiveBarrier] Tick $nextTick: notified $activeCount/${localTimeManagers.size} LTMs"
      )
    if (activeCount > 0) {
      lastGTMBroadcastWall = System.currentTimeMillis()
      lastGTMBroadcastTick = nextTick
    }
    if (activeCount == 0) {
      calculateAndBroadcastNextGlobalTick()
    }
  }

  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
  }

  protected def advanceToNextTick(): Unit = {
  }

  protected def nextTick: Option[Tick] =
    if (localTickOffset - initialTick >= simulationDuration) {
      None
    } else {
      Some(localTickOffset + 1)
    }

  /** Handles a migration pause request from LoadBalanceManager.
    *
    * The GlobalTimeManager will finish processing all current tick's spontaneous events (via
    * LocalTimeManagers) but will NOT advance to the next tick. Once all LocalTimeManagers have
    * reported completion of the current tick, it sends MigrationSafeEvent back to the
    * LoadBalanceManager with the current tick, confirming it's safe to migrate.
    */
  private def handleMigrationPauseRequest(event: RequestMigrationPauseEvent): Unit = {
    logInfo(
      s"Migration pause requested for shards ${event.shardIds} at tick $localTickOffset. " +
        s"Will pause after current tick completes."
    )
    migrationPauseRequested = true
    migrationRequester = event.requester

    if (localTimeManagers.values.forall(_.isProcessed)) {
      logInfo(s"All local managers idle. Signaling migration safe at tick $localTickOffset")
      migrationRequester ! MigrationSafeEvent(currentTick = localTickOffset)
    }
  }

  /** Handles migration completion notification from LoadBalanceManager. Resumes normal tick
    * advancement.
    */
  private def handleMigrationComplete(): Unit = {
    logInfo(s"Migration complete. Resuming tick advancement from tick $localTickOffset")
    migrationPauseRequested = false
    migrationRequester = null
    calculateAndBroadcastNextGlobalTick()
  }

  /** Request progressive loading of actors starting from currentTick. Sends a large max horizon;
    * the PLM decides the actual window size based on actor density (adaptive window sizing).
    */
  private def requestProgressiveLoad(currentTick: Tick): Unit =
    if (progressiveLoadManager != null) {
      progressiveLoadRequestedNanos = System.nanoTime()
      val horizonTick = currentTick + maxLookAheadTicks
      progressiveLoadManager ! TickWindowRequest(
        currentTick = currentTick,
        horizonTick = horizonTick
      )
    }

  /** Handle response from ProgressiveLoadDataManager confirming actors are ready.
    */
  private def handleTickWindowReady(event: TickWindowReady): Unit = {
    val previousLoadedUpTo = progressiveLoadedUpToTick
    progressiveLoadedUpToTick = event.readyUpToTick
    if (previousLoadedUpTo >= 0 && event.readyUpToTick > previousLoadedUpTo) {
      lastWindowTickRange = event.readyUpToTick - previousLoadedUpTo
    }

    if (event.readyUpToTick == Long.MaxValue) {
      onProgressiveLoadingComplete()
    }

    if (progressiveLoadRequestedNanos > 0) {
      val windowSec = (System.nanoTime() - progressiveLoadRequestedNanos) / 1e9
      ProgressiveLoadingMetrics.windowLoadDurationSeconds.observe(windowSec)
      progressiveLoadRequestedNanos = 0L
    }

    if (waitingForInitialWindow) {
      waitingForInitialWindow = false
      lastWindowTickRange = Math.max(1, event.readyUpToTick - initialTick)
      logInfo(
        s"Initial progressive window loaded up to tick ${event.readyUpToTick} " +
          s"(${event.actorsCreated} actors, range=$lastWindowTickRange ticks). Starting simulation now."
      )
      pendingStartEvent.foreach {
        startEvent =>
          pendingStartEvent = None
          PhaseMetrics.recordPhaseStart("simulation")
          logInfo(
            s"[StartupPhase] simulation_clock_released_after_first_window: readyUpTo=${event.readyUpToTick}, actorsCreated=${event.actorsCreated}"
          )
          startTime = System.currentTimeMillis()
          notifyLocalManagers(startEvent)
      }
      return
    }

    logDebug(
      s"Progressive load ready up to tick ${event.readyUpToTick}, " +
        s"${event.actorsCreated} actors created in this window"
    )

    if (waitingForProgressiveLoad) {
      waitingForProgressiveLoad = false
      TimeManagerMetrics.tmWaitingForProgressive.set(0)
      if (progressiveLoadBlockedNanos > 0) {
        val blockedSec = (System.nanoTime() - progressiveLoadBlockedNanos) / 1e9
        ProgressiveLoadingMetrics.blockedWaitDurationSeconds.observe(blockedSec)
        progressiveLoadBlockedNanos = 0L
      }
      pendingNextTick.foreach {
        tick =>
          if (tick <= progressiveLoadedUpToTick) {
            if (migrationPauseRequested) {
              logInfo(
                s"Progressive window ready at tick $tick but migration pause active — " +
                  s"holding until migration completes"
              )
            } else {
              val hasCurrentWork = localTimeManagers.values.exists(_.hasSchedule)
              if (tick <= localTickOffset || hasCurrentWork) {
                logInfo(
                  s"Progressive window ready at tick $tick but simulation has already advanced " +
                    s"(localTickOffset=$localTickOffset, hasWork=$hasCurrentWork). " +
                    s"Skipping CASE 2 broadcast to avoid tick-skip corruption."
                )
                pendingNextTick = None
                if (localTimeManagers.values.forall(_.isProcessed)) {
                  calculateAndBroadcastNextGlobalTick()
                }
              } else {
                logDebug(s"Resuming simulation after progressive load, advancing to tick $tick")
                pendingNextTick = None
                localTimeManagers.keys.foreach {
                  timeManager =>
                    localTimeManagers.update(
                      timeManager,
                      LocalTimeManagerTickInfo(tick = tick)
                    )
                }
                notifyLocalManagers(UpdateGlobalTimeEvent(tick))
              }
            }
          } else {
            logInfo(
              s"Progressive window loaded up to $progressiveLoadedUpToTick but pendingNextTick=$tick " +
                s"is still beyond loaded range. Requesting next progressive window."
            )
            waitingForProgressiveLoad = true
            requestProgressiveLoad(tick)
          }
      }
    }
  }

  /** Called when the ProgressiveLoadDataManager signals all progressive sources are exhausted.
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
        StopSimulationEvent()
      )
      simulationManager ! org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent()
      CoordinatedShutdown(context.system).run(CoordinatedShutdown.JvmExitReason)
    }
  }
}

object ConservativeGlobalTimeManager {
  def props(
    simulationDuration: Tick,
    extendSimulationIfPendingEventsAfterEnd: Boolean,
    simulationManager: ActorRef
  ): Props =
    Props(
      classOf[ConservativeGlobalTimeManager],
      simulationDuration,
      extendSimulationIfPendingEventsAfterEnd,
      simulationManager
    )
}
