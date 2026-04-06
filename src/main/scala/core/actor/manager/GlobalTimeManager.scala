package org.interscity.htc
package core.actor.manager

import core.entity.control.{ LocalTimeManagerTickInfo, ScheduledActors }
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.entity.state.DefaultState
import core.types.Tick

import org.apache.pekko.actor.{ ActorRef, Props }
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ LocalTimeReportEvent, RegisterActorEvent, StartSimulationTimeEvent, UpdateGlobalTimeEvent }
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.entity.event.control.load.{ TickWindowReady, TickWindowRequest }
import org.interscity.htc.core.entity.event.control.load.RegisterProgressiveLoadManagerEvent
import org.interscity.htc.core.util.ManagerConstantsUtil.{ GLOBAL_TIME_MANAGER_ACTOR_NAME, POOL_TIME_MANAGER_ACTOR_NAME }

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

  // Progressive loading coordination
  private var progressiveLoadManager: ActorRef = _
  private var progressiveLoadingEnabled = false
  private var progressiveLoadedUpToTick: Tick = Long.MaxValue
  private var maxLookAheadTicks: Tick = 10_000L
  private var waitingForProgressiveLoad = false
  private var pendingNextTick: Option[Tick] = None
  private var progressiveLoadingComplete = false

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
    val totalInstances = 8 // Reduced from 64 to reduce log spam
    val maxInstancesPerNode = Math.max(4, totalInstances / 2)
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
    case event: RegisterProgressiveLoadManagerEvent =>
      handleRegisterProgressiveLoadManager(event)
    case event: TickWindowReady =>
      handleTickWindowReady(event)
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
      logInfo(
        s"Registered LocalTimeManager: ${event.actorRef.path.name} - Total registered: ${localTimeManagers.size}"
      )
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
      calculateAndBroadcastNextGlobalTick()
    } else {
      logDebug(s"Waiting for more reports: $processedCount/$totalCount processed")
    }
  }

  private def calculateAndBroadcastNextGlobalTick(): Unit = {
    val totalManagers = localTimeManagers.size
    val scheduled = localTimeManagers.values.filter(_.hasSchedule)
    val scheduledCount = scheduled.size

    if (scheduled.isEmpty) {
      logInfo("No more scheduled events across local time managers. Terminating simulation")
      terminateSimulation()
      return
    }

    val nextTick = scheduled.map(_.tick).min
//    logInfo(s"Global tick coordination: selected nextTick=$nextTick from $scheduledCount scheduled manager(s)")

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
      pendingNextTick.foreach { tick =>
        if (tick <= progressiveLoadedUpToTick) {
          logInfo(s"Resuming simulation after progressive load, advancing to tick $tick")
          pendingNextTick = None

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
