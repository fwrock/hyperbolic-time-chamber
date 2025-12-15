package org.interscity.htc
package core.actor.manager

import core.entity.event.{EntityEnvelopeEvent, FinishEvent, SpontaneousEvent}
import core.types.Tick

import org.apache.pekko.actor.{ActorRef, Props}
import core.entity.control.{LocalTimeManagerTickInfo, ScheduledActors}
import core.entity.state.TimeManagerState

import org.apache.pekko.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ActorDestructionLotEvent, DestructEvent, LocalTimeReportEvent, PauseSimulationEvent, RegisterActorEvent, ResumeSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent, TimeManagerDelaySyncEvent, UpdateGlobalTimeEvent}
import org.interscity.htc.core.entity.event.control.execution.{TimeManagerRegisterEvent, UpdateGlobalTimeWindow, LocalTimeWindowReport}
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.util.{ManagerConstantsUtil, StringUtil}
import org.interscity.htc.core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, LOAD_MANAGER_ACTOR_NAME, POOL_TIME_MANAGER_ACTOR_NAME}

import scala.collection.mutable

class TimeManager(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  val parentManager: Option[ActorRef]
) extends BaseManager[TimeManagerState](
      timeManager = null,
      actorId =
        if parentManager.isEmpty then s"$LOAD_MANAGER_ACTOR_NAME-${System.nanoTime()}"
        else GLOBAL_TIME_MANAGER_ACTOR_NAME,
    ) {

  state = TimeManagerState()
  
  // Load lookahead configuration
  private val lookaheadWindow: Tick = try {
    config.getInt("htc.time-manager.lookahead-window")
  } catch {
    case _: Exception => 1 // Default to conservative (no lookahead)
  }
  state.lookaheadWindow = lookaheadWindow
  
  // Load metrics configuration
  private val metricsLogInterval: Tick = try {
    config.getInt("htc.time-manager.metrics-log-interval")
  } catch {
    case _: Exception => 500 // Default: log every 500 ticks
  }
  
  // Load window configuration
  private val windowSize: Tick = try {
    config.getInt("htc.time-manager.window-size")
  } catch {
    case _: Exception => 1 // Default: no window (tick-by-tick)
  }
  state.windowSize = windowSize
  state.windowExecutionEnabled = windowSize > 1

  private var selfProxy: ActorRef = null

  override def onStart(): Unit =
    if (parentManager.isEmpty) {
      createTimeManagersPool()
    } else {
      parentManager.get ! TimeManagerRegisterEvent(actorRef = self)
    }

  private def createTimeManagersPool(): Unit = {
    val totalInstances = config.getInt("htc.time-manager.total-instances")
    val maxInstancesPerNode = config.getInt("htc.time-manager.max-instances-per-node")

    logInfo(s"Creating TimeManager pool: totalInstances=$totalInstances, maxInstancesPerNode=$maxInstancesPerNode")
    logInfo(s"Lookahead optimization: window=${state.lookaheadWindow} ticks")
    if (state.windowExecutionEnabled) {
      logInfo(s"Window-based execution: windowSize=${state.windowSize} ticks (Strategy 2 - reduces barriers by ${state.windowSize}x)")
    } else {
      logInfo(s"Window-based execution: DISABLED (using tick-by-tick synchronization)")
    }

    state.timeManagersPool = context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(0),
        ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(TimeManager.props(simulationDuration, simulationManager, Some(getSelfProxy))),
      name = POOL_TIME_MANAGER_ACTOR_NAME
    )
    simulationManager ! TimeManagerRegisterEvent(actorRef = state.timeManagersPool)
  }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterActorEvent    => registerActor(register)
    case schedule: ScheduleEvent         => scheduleApply(schedule)
    case finish: FinishEvent             => finishEventApply(finish)
    case spontaneous: SpontaneousEvent   => if (isRunning) actSpontaneous(spontaneous)
    case PauseSimulationEvent            => if (isRunning) pauseSimulation()
    case ResumeSimulationEvent           => resumeSimulation()
    case StopSimulationEvent             => stopSimulation()
    case e: UpdateGlobalTimeEvent        => syncWithGlobalTime(e.tick)
    case w: UpdateGlobalTimeWindow       => syncWithGlobalTimeWindow(w.windowStart, w.windowEnd)
    case timeManagerRegisterEvent: TimeManagerRegisterEvent =>
      registerTimeManager(timeManagerRegisterEvent)
    case localTimeReport: LocalTimeReportEvent =>
      handleLocalTimeReport(sender(), localTimeReport.tick, localTimeReport.hasScheduled)
    case windowReport: LocalTimeWindowReport =>
      handleLocalTimeWindowReport(sender(), windowReport.windowEnd, windowReport.hasScheduled)
    case timeManagerDelaySyncEvent: TimeManagerDelaySyncEvent =>
      if (parentManager.isEmpty) {
        calculateAndBroadcastNextGlobalTick()
      }
    case actorDestructionLotEvent: ActorDestructionLotEvent =>
      onActorDestructionLot(actorDestructionLotEvent)
  }

  private def registerTimeManager(timeManagerRegisterEvent: TimeManagerRegisterEvent): Unit =
    state.localTimeManagers.put(
      timeManagerRegisterEvent.actorRef,
      LocalTimeManagerTickInfo(
        tick = state.localTickOffset
      )
    )

  private def registerActor(event: RegisterActorEvent): Unit = {
    state.registeredActors.add(event.actorId)
    scheduleApply(
      ScheduleEvent(tick = event.startTick, actorRef = event.actorId, identify = event.identify)
    )
  }

  private def onActorDestructionLot (event: ActorDestructionLotEvent): Unit = {
    state.countDestruction += event.amount
    logInfo(s"Total destroyed actors: ${state.countDestruction}")
  }

  private def startSimulation(start: StartSimulationTimeEvent): Unit = {
    logInfo(s"Started simulation: ${start.startTick}")
    unstashAll()
    start.data match
      case Some(data) =>
        state.startTime = data.startTime
      case _ =>
        state. startTime = System.currentTimeMillis()
    state.initialTick = start.startTick
    state.localTickOffset = state.initialTick
    state.isPaused = false
    state.isStopped = false
    // Initialize window state
    if (state.windowExecutionEnabled) {
      state.currentWindowStart = state.initialTick
      state.currentWindowEnd = Math.min(state.initialTick + state.windowSize, simulationDuration)
    }
    // Initialize metrics tracking
    val now = System.currentTimeMillis()
    state.simulationStartWallTime = now
    state.lastMetricsTick = state.initialTick
    state.lastMetricsTime = now
    state.ticksProcessedSinceLastMetric = 0
    if (parentManager.isEmpty) {
      logInfo(s"Global TimeManager started at tick ${state.localTickOffset}")
      if (state.windowExecutionEnabled) {
        notifyLocalManagers(UpdateGlobalTimeWindow(state.currentWindowStart, state.currentWindowEnd))
      } else {
        notifyLocalManagers(start)
      }
    } else {
      logInfo(
        s"Local TimeManager started at tick ${state.localTickOffset}"
      )
      self ! UpdateGlobalTimeEvent(state.localTickOffset)
    }
  }

  private def pauseSimulation(): Unit = {
    state.isPaused = true
    if (parentManager.isEmpty) {
      notifyLocalManagers(PauseSimulationEvent)
    }
  }

  private def resumeSimulation(): Unit =
    if (state.isPaused) {
      state.isPaused = false
      self ! SpontaneousEvent(tick = state.localTickOffset, actorRef = self)
      if (parentManager.isEmpty) {
        notifyLocalManagers(ResumeSimulationEvent)
      }
    }

  private def stopSimulation(): Unit = {
    state.isStopped = true
    terminateSimulation()
  }

  private def syncWithGlobalTime(globalTick: Tick): Unit = {
    state.localTickOffset = globalTick
    state.tickOffset = globalTick - state.initialTick
    if (parentManager.nonEmpty) {
      if (isRunning) {
        if (state.localTickOffset - state.initialTick >= simulationDuration) {
          terminateSimulation()
        } else {
          processTick(state.localTickOffset)
        }
      }
    }
  }
  
  private def syncWithGlobalTimeWindow(windowStart: Tick, windowEnd: Tick): Unit = {
    state.currentWindowStart = windowStart
    state.currentWindowEnd = windowEnd
    state.localTickOffset = windowStart
    state.tickOffset = windowStart - state.initialTick
    
    if (parentManager.nonEmpty) {
      if (isRunning) {
        if (windowEnd >= simulationDuration) {
          terminateSimulation()
        } else {
          processWindow(windowStart, windowEnd)
        }
      }
    }
  }

  private def handleLocalTimeReport(
    localManager: ActorRef,
    tick: Tick,
    hasScheduled: Boolean
  ): Unit =
    if (parentManager.isEmpty) {
      state.localTimeManagers.update(
        localManager,
        LocalTimeManagerTickInfo(
          tick = tick,
          hasSchedule = hasScheduled,
          isProcessed = true
        )
      )
      if (state.localTimeManagers.values.forall(_.isProcessed)) {
        calculateAndBroadcastNextGlobalTick()
      }
    }
  
  private def handleLocalTimeWindowReport(
    localManager: ActorRef,
    windowEnd: Tick,
    hasScheduled: Boolean
  ): Unit =
    if (parentManager.isEmpty) {
      state.localTimeManagers.update(
        localManager,
        LocalTimeManagerTickInfo(
          tick = windowEnd,
          hasSchedule = hasScheduled,
          isProcessed = true
        )
      )
      if (state.localTimeManagers.values.forall(_.isProcessed)) {
        calculateAndBroadcastNextWindow()
      }
    }

  private def calculateAndBroadcastNextGlobalTick(): Unit = {
    val scheduled = state.localTimeManagers.values.filter(_.hasSchedule)
    val nextTick = if (scheduled.nonEmpty) {
      scheduled.map(_.tick).min
    } else {
      state.localTimeManagers.values.filter(_.isProcessed).map(_.tick).min
    }
    state.localTickOffset = nextTick
    state.tickOffset = nextTick - state.initialTick
    state.localTimeManagers.keys.foreach {
      timeManager =>
        state.localTimeManagers.update(
          timeManager,
          LocalTimeManagerTickInfo(
            tick = nextTick
          )
        )
    }
    notifyLocalManagers(UpdateGlobalTimeEvent(state.localTickOffset))
  }
  
  private def calculateAndBroadcastNextWindow(): Unit = {
    val scheduled = state.localTimeManagers.values.filter(_.hasSchedule)
    val nextWindowStart = if (scheduled.nonEmpty) {
      scheduled.map(_.tick).min
    } else {
      state.localTimeManagers.values.filter(_.isProcessed).map(_.tick).min
    }
    
    val nextWindowEnd = Math.min(
      nextWindowStart + state.windowSize,
      simulationDuration
    )
    
    state.currentWindowStart = nextWindowStart
    state.currentWindowEnd = nextWindowEnd
    state.localTickOffset = nextWindowStart
    state.tickOffset = nextWindowStart - state.initialTick
    
    state.localTimeManagers.keys.foreach {
      timeManager =>
        state.localTimeManagers.update(
          timeManager,
          LocalTimeManagerTickInfo(
            tick = nextWindowStart
          )
        )
    }
    
    notifyLocalManagers(UpdateGlobalTimeWindow(nextWindowStart, nextWindowEnd))
  }

  private def notifyLocalManagers(event: Any): Unit =
    state.localTimeManagers.keys.foreach {
      localManager =>
        localManager ! event
    }

  private def scheduleApply(schedule: ScheduleEvent): Unit = {
    state.countScheduled += 1
    state.scheduledActors.get(schedule.tick) match
      case Some(scheduled) =>
        schedule.identify.foreach(scheduled.actorsRef.add)
      case None =>
        state.scheduledActors.put(
          schedule.tick,
          ScheduledActors(tick = schedule.tick, actorsRef = mutable.Set(schedule.identify.get))
        )
  }

  private def getLabel: String =
    if parentManager.isEmpty then "GlobalTimeManager" else "LocalTimeManager"

  private def finishEventApply(finish: FinishEvent): Unit =
    if (finish.timeManager == self) {
      finish.scheduleTick.map(_.toLong).foreach(state.scheduledTicksOnFinish.add)
      state.runningEvents.filterInPlace(_.id != finish.identify.id)
      finishDestruct(finish)
      advanceToNextTick()
      reportGlobalTimeManager(true)
    } else {
      finish.timeManager ! finish
    }

  private def finishDestruct(finish: FinishEvent): Unit =
    if (finish.destruct) {
      state.registeredActors.remove(finish.identify.id)
      state.countDestruction += 1
      if (state.countDestruction % 1000 == 0) {
        reportDestroyedGlobalTimeManager()
      }
      sendDestructEvent(finish)
    }

  override def actSpontaneous(spontaneous: SpontaneousEvent): Unit =
    if (isRunning) {
      // Initialize wall time on first tick (for Local TMs)
      if (state.simulationStartWallTime == 0) {
        state.simulationStartWallTime = System.currentTimeMillis()
      }
      
      if (state.localTickOffset - state.initialTick >= simulationDuration) {
        terminateSimulation()
      } else {
        processTick(spontaneous.tick)
      }
    }

  private def processTick(tick: Tick): Unit =
    if (state.scheduledActors.nonEmpty) {
      processNextEventTick(tick)
    } else {
      advanceToNextTick()
      reportGlobalTimeManager()
    }
  
  private def processWindow(windowStart: Tick, windowEnd: Tick): Unit = {
    var currentTick = windowStart
    var hasProcessedEvents = false
    
    while (currentTick < windowEnd && isRunning) {
      state.localTickOffset = currentTick
      
      if (state.scheduledActors.contains(currentTick)) {
        processNextEventTick(currentTick)
        hasProcessedEvents = true
      }
      
      currentTick += 1
    }
    
    state.localTickOffset = windowEnd
    reportWindowCompletion(windowEnd, hasProcessedEvents)
  }
  
  private def reportWindowCompletion(windowEnd: Tick, hasScheduled: Boolean): Unit =
    if (parentManager.isDefined && state.runningEvents.isEmpty) {
      state.scheduledTicksOnFinish.clear()
      parentManager.get ! LocalTimeWindowReport(
        windowEnd = windowEnd,
        hasScheduled = hasScheduled || state.scheduledActors.nonEmpty,
        actorRef = getPath
      )
    }

  private def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit =
    if (parentManager.isDefined && state.runningEvents.isEmpty) {
      state.scheduledTicksOnFinish.clear()
      parentManager.get ! LocalTimeReportEvent(
        tick = state.localTickOffset,
        hasScheduled = hasScheduled,
        actorRef = getPath
      )
    }

  private def reportDestroyedGlobalTimeManager(): Unit =
    if (parentManager.isDefined) {
      parentManager.get ! ActorDestructionLotEvent(
        amount = state.countDestruction
      )
    }

  private def processNextEventTick(tick: Tick): Unit =
    state.scheduledActors.get(tick) match
      case Some(scheduled) =>
        scheduled.actorsRef.foreach {
          actor => state.runningEvents.add(actor)
        }
        state.totalActorWakeups += scheduled.actorsRef.size
        sendSpontaneousEvent(tick, scheduled.actorsRef)
        state.scheduledActors.remove(tick)
      case None =>
        state.idleTicksCount += 1
        advanceToNextTick()
        reportGlobalTimeManager()

  private def sendSpontaneousEvent(tick: Tick, actorsRef: mutable.Set[Identify]): Unit = {
    val safeHorizon = calculateSafeHorizon(tick)
    state.totalSpontaneousEventsSent += actorsRef.size
    
    // Progress logging every 100 ticks (Local TM only - they process actual ticks)
    // Log only from first Local TM to avoid spam (they all process same tick range)
    if (parentManager.isDefined && tick % 500 == 0 && actorsRef.nonEmpty) {
      val lookaheadTicks = safeHorizon - tick
      val progress = (tick.toDouble / simulationDuration * 100).toInt
      val windowInfo = if (state.windowExecutionEnabled) f"Win:${state.windowSize}%2d" else "Win:OFF"
      
      // Throughput metrics
      val nowMs = System.currentTimeMillis()
      val elapsedMs = Math.max(1, nowMs - state.simulationStartWallTime)  // Avoid division by zero
      val elapsedSec = elapsedMs / 1000.0
      val ticksProcessed = Math.max(1, tick - state.initialTick)  // Avoid division by zero
      val throughput = if (elapsedSec > 0.1) (ticksProcessed / elapsedSec).toInt else 0  // Wait at least 100ms
      
      // Efficiency metrics
      val avgActorsPerTick = state.totalActorWakeups / ticksProcessed
      val idleRatio = Math.min(100, (state.idleTicksCount.toDouble / ticksProcessed * 100).toInt)  // Cap at 100%
      
      // ETA calculation
      val remainingTicks = simulationDuration - tick
      val etaSec = if (throughput > 0) remainingTicks / throughput else 0
      val etaMin = etaSec / 60
      val etaStr = if (etaMin > 60) f"${etaMin/60}%.1fh" else if (etaMin > 1) f"${etaMin}%.0fm" else f"${etaSec}%.0fs"
      
      logInfo(f"⏱️  TICK $tick%5d/$simulationDuration ($progress%3d%%) | $windowInfo | Active:${actorsRef.size}%4d (avg:$avgActorsPerTick) | Idle:$idleRatio%2d%% | LA:+$lookaheadTicks | ⚡$throughput%5d t/s | ⏳$etaStr%6s")
    }
    
    actorsRef.foreach {
      actor =>
        sendSpontaneousEvent(tick, actor, safeHorizon)
    }
  }

  private def sendSpontaneousEvent(tick: Tick, identity: Identify, safeHorizon: Tick = -1): Unit =
    if (identity.actorType == CreationTypeEnum.PoolDistributed.toString) {
      sendSpontaneousEventPool(tick, identity, safeHorizon)
    } else {
      sendSpontaneousEventShard(tick, identity, safeHorizon)
    }

  private def sendSpontaneousEventShard(tick: Tick, identity: Identify, safeHorizon: Tick = -1): Unit =
    getShardRef(StringUtil.getModelClassName(identity.classType)) ! EntityEnvelopeEvent(
      identity.id,
      SpontaneousEvent(
        tick = tick,
        actorRef = self,
        safeHorizon = safeHorizon
      )
    )

  private def sendSpontaneousEventPool(tick: Tick, identity: Identify, safeHorizon: Tick = -1): Unit =
    getActorPoolRef(identity.id) ! SpontaneousEvent(
      tick = tick,
      actorRef = self,
      safeHorizon = safeHorizon
    )

  /** Log throughput metrics (ticks per second) */
  private def logThroughputMetrics(currentTick: Tick): Unit = {
    if (metricsLogInterval <= 0 || parentManager.isDefined) {
      return // Metrics disabled or local TM (only global logs)
    }
    
    val ticksSinceLastLog = currentTick - state.lastMetricsTick
    
    if (ticksSinceLastLog >= metricsLogInterval) {
      val currentTime = System.currentTimeMillis()
      val elapsedTimeMs = currentTime - state.lastMetricsTime
      
      if (elapsedTimeMs > 0) {
        val ticksPerSecond = (ticksSinceLastLog * 1000.0) / elapsedTimeMs
        val totalTicksProcessed = currentTick - state.initialTick
        val totalElapsedMs = currentTime - state.startTime
        val avgTicksPerSecond = if (totalElapsedMs > 0) {
          (totalTicksProcessed * 1000.0) / totalElapsedMs
        } else {
          0.0
        }
        
        logInfo(f"Throughput: $ticksPerSecond%.2f ticks/s (instant), $avgTicksPerSecond%.2f ticks/s (avg) | " +
                f"Tick: $currentTick | Total ticks: $totalTicksProcessed | " +
                f"Elapsed: ${totalElapsedMs/1000.0}%.1f s")
      }
      
      // Reset metrics for next interval
      state.lastMetricsTick = currentTick
      state.lastMetricsTime = currentTime
      state.ticksProcessedSinceLastMetric = 0
    }
  }

  /** Calculate the safe horizon for speculative execution
    * Actors can safely advance up to this tick without external synchronization
    */
  private def calculateSafeHorizon(currentTick: Tick): Tick = {
    if (state.lookaheadWindow <= 1) {
      return currentTick // No lookahead, conservative mode
    }
    
    // Calculate horizon based on next scheduled events
    val nextScheduledTick = state.scheduledActors.keys.minOption.getOrElse(currentTick + state.lookaheadWindow)
    val horizon = Math.min(
      currentTick + state.lookaheadWindow,
      nextScheduledTick
    )
    
    // Never exceed simulation duration
    Math.min(horizon, simulationDuration)
  }

  private def advanceToNextTick(): Unit = {
    val newTick = nextTick
    if (state.localTickOffset < simulationDuration) {
      state.localTickOffset = newTick
      state.scheduledTicksOnFinish.clear()
      // Log throughput metrics periodically
      logThroughputMetrics(newTick)
    } else {
      terminateSimulation()
    }
  }

  private def nextTick: Tick = {
    if (state.runningEvents.isEmpty) {
      return (state.scheduledActors.nonEmpty, state.runningEvents.isEmpty) match {
        case (true, true) =>
          val newScheduled = state.scheduledTicksOnFinish.minOption.getOrElse(state.localTickOffset + 1)
          val currentScheduled = state.scheduledActors.keys.minOption.getOrElse(state.localTickOffset + 1)
          if (newScheduled < currentScheduled) {
            newScheduled
          } else {
            currentScheduled
          }
        case _ => state.scheduledTicksOnFinish.minOption.getOrElse(state.localTickOffset + 1)
      }
    }
    state.localTickOffset
  }

  private def terminateSimulation(): Unit = {
    if (state.runningEvents.isEmpty && state.scheduledActors.isEmpty) {
      if (parentManager.isEmpty) {
        notifyLocalManagers(StopSimulationEvent)
      }
      context.stop(self)
    }
    printSimulationDuration()
  }

  private def sendDestructEvent(finishEvent: FinishEvent): Unit =
    getShardRef(finishEvent.identify.classType) ! EntityEnvelopeEvent(
      finishEvent.identify.id,
      DestructEvent(tick = state.localTickOffset, actorRef = getPath)
    )

  private def printSimulationDuration(): Unit = {
    val duration = System.currentTimeMillis() - state.simulationStartWallTime
    if (duration > 0) {  // Only print if timing was initialized
      logInfo(s"$getLabel - Simulation endTick: ${state.localTickOffset}")
      logInfo(s"$getLabel - Simulation total ticks: ${state.localTickOffset - state.initialTick}")
      logInfo(s"$getLabel - Simulation duration: $duration ms")
      logInfo(s"$getLabel - Simulation duration: ${duration / 1000.0} s")
      val throughput = if (duration > 0) ((state.localTickOffset - state.initialTick) * 1000.0 / duration).toInt else 0
      logInfo(s"$getLabel - Average throughput: $throughput ticks/s")
    }
  }

  private def isRunning: Boolean = !state.isPaused && !state.isStopped

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }
}

object TimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    parentManager: Option[ActorRef]
  ): Props =
    Props(classOf[TimeManager], simulationDuration, simulationManager, parentManager)
}
