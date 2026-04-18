package org.interscity.htc
package core.actor.manager.time


import core.entity.event.control.execution.TimeManagerRegisterEvent
import core.entity.event.{EntityEnvelopeEvent, FinishEvent, SpontaneousEvent}
import core.enumeration.CreationTypeEnum
import core.metrics.MetricsServer
import core.types.Tick
import core.util.{IdUtil, StringUtil}

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.*

import scala.collection.mutable
import scala.concurrent.duration.*

/** Base abstract class for local time managers. Local time managers handle the actual execution of
  * simulation events and report progress back to the global time manager.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  * @param parentManager
  *   Reference to the global time manager
  */
abstract class LocalTimeManagerBase(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  val parentManager: Option[ActorRef],
  actorId: String
) extends TimeManagerBase(
      timeManager = null,
      actorId = actorId
    )
    with MicroAwareTimeManager {

  protected var countScheduled = 0
  private var selfProxy: ActorRef = null
  @volatile private var isTerminated = false
  private val registeredIdentities: mutable.Map[String, Identify] = mutable.Map()

  // --- Watchdog: detects and recovers from stuck runningEvents ---
  // When actors don't respond with FinishEvent (e.g., cross-pod message loss during
  // cluster formation, shard rebalancing, or actor crash), the watchdog retries
  // sending SpontaneousEvent once before force-clearing.
  private case object RunningEventsWatchdog
  private val WATCHDOG_INTERVAL_SECONDS: Int =
    try config.getInt("htc.time-manager.watchdog-interval-seconds") catch { case _: Exception => 30 }
  private val STALE_WARNING_SECONDS: Int =
    try config.getInt("htc.time-manager.stale-warning-seconds") catch { case _: Exception => 60 }
  private val FORCE_CLEAR_SECONDS: Int =
    try config.getInt("htc.time-manager.force-clear-seconds") catch { case _: Exception => 120 }
  protected var tickProcessingStartTime: Long = 0L
  private var lastWatchdogRunningCount: Int = 0
  private var consecutiveStaleChecks: Int = 0
  private var watchdogTask: org.apache.pekko.actor.Cancellable = _
  private var retriedStuckActors: Boolean = false

  /** Reset watchdog counters when starting a new batch of events. */
  protected def resetWatchdogState(): Unit = {
    tickProcessingStartTime = System.currentTimeMillis()
    consecutiveStaleChecks = 0
    lastWatchdogRunningCount = 0
    retriedStuckActors = false
  }

  override def onStart(): Unit = {
    if (parentManager.nonEmpty) {
      // Register this specific instance with the global manager
      parentManager.get ! TimeManagerRegisterEvent(actorRef = self)
    }
    // Start watchdog to detect stuck runningEvents (e.g. from shard rebalancing)
    watchdogTask = context.system.scheduler.scheduleWithFixedDelay(
      WATCHDOG_INTERVAL_SECONDS.seconds,
      WATCHDOG_INTERVAL_SECONDS.seconds,
      self,
      RunningEventsWatchdog
    )(context.dispatcher)
  }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterActorEvent    => registerActor(register)
    case schedule: ScheduleEvent         => scheduleEvent(schedule)
    case finish: FinishEvent             => finishEvent(finish)
    case spontaneous: SpontaneousEvent   => if (isRunning) onSpontaneousEvent(spontaneous)
    case e: UpdateGlobalTimeEvent        => syncWithGlobalTime(e.tick)
    case _: org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent =>
      stopSimulation()
      forceDestructActiveActors()
      terminateSimulation()
    case RunningEventsWatchdog           => handleRunningEventsWatchdog()
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Local TimeManager started at tick ${event.startTick}")
    event.data.foreach(
      data => startTime = data.startTime
    )
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    self ! UpdateGlobalTimeEvent(localTickOffset)
  }

  protected def registerActor(event: RegisterActorEvent): Unit = {
    registeredActors.add(event.actorId)
    event.identify.foreach {
      identity =>
        registeredIdentities.put(event.actorId, identity)
        // Prometheus: track actor registration by type
        val actorType = identity.classType.split('.').lastOption.getOrElse(identity.classType)
        MetricsServer.actorsRegistered.labels(actorType).inc()
        MetricsServer.activeActors.labels(actorType).inc()
    }
    scheduleEvent(
      ScheduleEvent(tick = event.startTick, actorRef = event.actorId, identify = event.identify)
    )
  }

  protected def scheduleEvent(event: ScheduleEvent): Unit = {
    countScheduled += 1
    // CRITICAL: If the requested tick is in the past (< localTickOffset), it has already
    // been processed and removed from scheduledActors by processTick. Any entry added at
    // that past tick is orphaned — nextTick filters out ticks < localTickOffset, so they
    // are never dispatched again. This happens when ScheduleEvent is routed via the pool
    // router to a TM that has already advanced past the requested tick (e.g. from MICRO
    // link handleEnterLinkMicro calling scheduleEvent while a "fast" TM receives it).
    // Use <= localTickOffset (not just <) to also guard against same-tick races where
    // processTick(T) already cleared scheduledActors[T] but localTickOffset is still T.
    // Fix: bump past-tick/same-tick requests to localTickOffset + 1, guaranteeing future processing.
    val effectiveTick = if (event.tick <= localTickOffset) {
      logDebug(
        s"[TM] ScheduleEvent tick=${event.tick} is at/behind localTickOffset=$localTickOffset; bumping to ${localTickOffset + 1}"
      )
      localTickOffset + 1
    } else {
      event.tick
    }
    val actorsSet = scheduledActors.getOrElseUpdate(effectiveTick, mutable.Set[Identify]())
    event.identify.foreach(actorsSet.add)
  }

  protected def finishEvent(finish: FinishEvent): Unit =
    if (finish.timeManager == self) {
      MetricsServer.eventsProcessed.labels("finish").inc()
      finish.scheduleTick.map(_.toLong).foreach(scheduledTicksOnFinish.add)
      val wasProcessingSpontaneousEvent = runningEvents.exists(_.id == finish.identify.id)
      runningEvents.filterInPlace(_.id != finish.identify.id)
      // Prometheus: update running events gauge
      MetricsServer.tmRunningEvents.set(runningEvents.size.toDouble)

      // If no scheduleTick provided (None), remove actor from ALL future scheduled ticks
      if (finish.scheduleTick.isEmpty) {
        val actorId = finish.identify.id
        val actorClass = finish.identify.classType
        var removedFromTicks = 0
        scheduledActors.foreach {
          case (tick, actors) =>
            val sizeBefore = actors.size
            actors.filterInPlace(_.id != actorId)
            if (actors.size < sizeBefore) removedFromTicks += 1
        }
        // Clean up empty tick entries
        scheduledActors.filterInPlace {
          case (_, actors) => actors.nonEmpty
        }
        if (removedFromTicks > 0) {
          logInfo(s"Unregistered ${actorClass} (${actorId}) from $removedFromTicks future ticks")
        }
      }

      finishDestruct(finish)
      // Only advance to the next tick if this actor was actually running a spontaneous event.
      // When onFinishSpontaneous is called from actInteractWith (not actSpontaneous), the actor
      // is NOT in runningEvents; advancing here would trigger a spurious hasScheduled=false
      // report to the global TM, causing premature simulation termination.
      if (wasProcessingSpontaneousEvent) {
        advanceToNextTick()
      }
    } else {
      finish.timeManager ! finish
    }

  private def finishDestruct(finish: FinishEvent): Unit =
    if (finish.destruct) {
      MetricsServer.eventsProcessed.labels("destruct").inc()
      registeredActors.remove(finish.identify.id)
      val removedIdentity = registeredIdentities.remove(finish.identify.id)
      // Prometheus: decrement active actors gauge
      removedIdentity.foreach { identity =>
        val actorType = identity.classType.split('.').lastOption.getOrElse(identity.classType)
        MetricsServer.activeActors.labels(actorType).dec()
      }
      sendDestructEvent(finish)
    }

  override protected def onSpontaneousEvent(spontaneous: SpontaneousEvent): Unit =
    if (isRunning && !isTerminated) {
      processTick(spontaneous.tick)
    }

  private def syncWithGlobalTime(globalTick: Tick): Unit = {
    if (globalTick % 10000 == 0) {
      logInfo(
        s"[LocalTM] Syncing with global tick $globalTick (previous localTick=$localTickOffset)"
      )
    }
    localTickOffset = globalTick
    tickOffset = globalTick - initialTick
    if (isRunning && !isTerminated) {
      // Trigger micro links before processing regular events
      triggerMicroLinks(globalTick)
      processTick(localTickOffset)
    }
  }

  /** Processes a simulation tick. Subclasses implement specific time management strategies.
    * @param tick
    *   The tick to process
    */
  protected def processTick(tick: Tick): Unit

  /** Advances to the next simulation tick. */
  protected def advanceToNextTick(): Unit =
    if (runningEvents.isEmpty) {
      nextTick match {
        case Some(tick) =>
          // Report to global and wait for sync instead of advancing locally
          reportGlobalTimeManager(hasScheduled = true)
        case None =>
          // No more events scheduled locally, report and wait
          reportGlobalTimeManager(hasScheduled = false)
      }
    }

  protected def nextTick: Option[Tick] = {
    val scheduled = scheduledActors.keys.filter(_ >= localTickOffset)
    val scheduledOnFinish = scheduledTicksOnFinish.filter(_ >= localTickOffset)
    val allTicks = scheduled ++ scheduledOnFinish

    if (allTicks.nonEmpty) {
      Some(allTicks.min)
    } else {
      None
    }
  }

  /** Process next scheduled event at given tick. NOTE: This is a utility method not directly used
    * by current implementations. See LocalDiscreteEventTimeManager and LocalTimeSteppedTimeManager
    * for actual processTick logic.
    */
  protected def processNextEventTick(tick: Tick): Unit = {
    localTickOffset = tick
    scheduledActors.get(tick).foreach {
      actorsSet =>
        logInfo(
          s"[Legacy] processNextEventTick: tick $tick with ${actorsSet.size} scheduled actors"
        )
        sendSpontaneousEvent(tick, actorsSet)
    }
    scheduledActors.remove(tick)
    scheduledTicksOnFinish.remove(tick)
  }

  protected def sendSpontaneousEvent(tick: Tick, actorsRef: mutable.Set[Identify]): Unit = {
    if (actorsRef.nonEmpty && runningEvents.isEmpty) {
      resetWatchdogState()
    }
    // Prometheus: track scheduled and dispatched events
    MetricsServer.tmScheduledActors.set(actorsRef.size.toDouble)
    MetricsServer.eventsProcessed.labels("spontaneous").inc(actorsRef.size.toDouble)
    actorsRef.foreach {
      identity =>
        runningEvents.add(identity)
        sendSpontaneousEvent(tick, identity)
    }
  }

  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    if (identity.actorType.isEmpty) {
      logWarn(s"Actor identity has empty actorType: $identity")
      return
    }

    CreationTypeEnum.valueOf(identity.actorType) match {
      case CreationTypeEnum.LoadBalancedDistributed =>
        sendSpontaneousEventShard(tick, identity)
      case CreationTypeEnum.PoolDistributed =>
        sendSpontaneousEventPool(tick, identity)
      case _ =>
        logWarn(s"Unknown creation type: ${identity.actorType} for actor ${identity.id}")
    }
  }

  private def sendSpontaneousEventShard(tick: Tick, identity: Identify): Unit = {
    val actorRef = getShardRef(StringUtil.getModelClassName(identity.classType))
    actorRef ! core.entity.event.EntityEnvelopeEvent(
      IdUtil.format(identity.id),
      SpontaneousEvent(tick = tick, actorRef = self)
    )
  }

  private def sendSpontaneousEventPool(tick: Tick, identity: Identify): Unit = {
    val actorRef = context.system.actorSelection(identity.actorRef)
    actorRef ! SpontaneousEvent(tick = tick, actorRef = self)
  }

  /**
   * Watchdog: detects stuck runningEvents and force-advances the simulation.
   * Prevents the LocalTM from being permanently stuck when actors
   * don't respond with FinishEvent (e.g. due to shard rebalancing, crashes,
   * or cross-pod message loss with at-most-once delivery).
   *
   * Strategy:
   * 1. After STALE_WARNING_SECONDS: log warning with stuck actor IDs
   * 2. After warning + 1 interval: RETRY sending SpontaneousEvent to stuck actors
   *    (handles transient message loss — the most common cause)
   * 3. After FORCE_CLEAR_SECONDS: force-clear and advance
   */
  private def handleRunningEventsWatchdog(): Unit = {
    if (runningEvents.nonEmpty && tickProcessingStartTime > 0) {
      val elapsedMs = System.currentTimeMillis() - tickProcessingStartTime
      val elapsedSec = elapsedMs / 1000
      val currentCount = runningEvents.size

      if (currentCount == lastWatchdogRunningCount && currentCount > 0) {
        consecutiveStaleChecks += 1
      } else {
        consecutiveStaleChecks = 0
      }
      lastWatchdogRunningCount = currentCount

      if (elapsedSec >= STALE_WARNING_SECONDS) {
        val sampleIds = runningEvents.take(5).map(_.id).mkString(", ")
        logWarn(
          s"[Watchdog] $currentCount running events stuck for ${elapsedSec}s at tick $localTickOffset " +
            s"(stale checks: $consecutiveStaleChecks). Sample IDs: $sampleIds"
        )

        // Retry: resend SpontaneousEvent to stuck actors once.
        // Most common cause is transient message loss (at-most-once Artery delivery)
        // during cluster formation or under heavy load. A single retry resolves most cases.
        if (!retriedStuckActors && consecutiveStaleChecks >= 1) {
          retriedStuckActors = true
          logInfo(
            s"[Watchdog] RETRYING SpontaneousEvent for $currentCount stuck actors at tick $localTickOffset"
          )
          runningEvents.foreach { identity =>
            sendSpontaneousEvent(localTickOffset, identity)
          }
        }
      }

      if (elapsedSec >= FORCE_CLEAR_SECONDS && consecutiveStaleChecks >= 2) {
        logWarn(
          s"[Watchdog] FORCE-CLEARING $currentCount stale running events at tick $localTickOffset " +
            s"after ${elapsedSec}s. Advancing to next tick."
        )
        runningEvents.clear()
        onWatchdogForceAdvance()
        advanceToNextTick()
      }
    }
  }

  /** Hook for subclasses to clean up state when watchdog force-advances. */
  protected def onWatchdogForceAdvance(): Unit = ()

  private def terminateSimulation(): Unit = synchronized {
    if (!isTerminated) {
      isTerminated = true
      if (watchdogTask != null) { watchdogTask.cancel(); watchdogTask = null }
      printSimulationDuration()
      logInfo("Local simulation terminated")
      reportGlobalTimeManager(hasScheduled = false)
    }
  }

  private def forceDestructActiveActors(): Unit = {
    val identitiesToDestruct = mutable.Map[String, Identify]()

    scheduledActors.values.foreach {
      identities =>
        identities.foreach {
          identity =>
            identitiesToDestruct.put(identity.id, identity)
        }
    }

    runningEvents.foreach {
      identity =>
        identitiesToDestruct.put(identity.id, identity)
    }

    if (identitiesToDestruct.nonEmpty) {
      logInfo(
        s"Force-destructing ${identitiesToDestruct.size} active/scheduled actors on simulation stop"
      )
    }

    identitiesToDestruct.values.foreach(sendDestructEvent)
  }

  protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit =
    if (parentManager.nonEmpty) {
      // Report the NEXT tick we want to process (not current tick)
      val reportTick = if (hasScheduled) nextTick.getOrElse(localTickOffset) else localTickOffset
      parentManager.get ! LocalTimeReportEvent(
        tick = reportTick,
        hasScheduled = hasScheduled,
        actorRef = self.path.toString
      )
    }

  private def sendDestructEvent(finishEvent: FinishEvent): Unit = {
    val actorRef = getActorRef(finishEvent.identify.actorRef)
    if (actorRef != null) {
      actorRef ! DestructEvent(actorRef = self.path.toString)
    }
  }

  private def sendDestructEvent(identity: Identify): Unit = {
    if (identity.actorType.isEmpty) {
      logWarn(s"Cannot destruct actor with empty actorType: ${identity.id}")
      return
    }

    CreationTypeEnum.valueOf(identity.actorType) match {
      case CreationTypeEnum.LoadBalancedDistributed =>
        val actorRef = getShardRef(StringUtil.getModelClassName(identity.classType))
        actorRef ! EntityEnvelopeEvent(
          IdUtil.format(identity.id),
          DestructEvent(actorRef = self.path.toString)
        )
      case CreationTypeEnum.PoolDistributed =>
        val actorRef = getActorRef(identity.actorRef)
        if (actorRef != null) {
          actorRef ! DestructEvent(actorRef = self.path.toString)
        }
      case _ =>
        logWarn(
          s"Unknown creation type on force-destruct: ${identity.actorType} for actor ${identity.id}"
        )
    }
  }

  protected def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = self
      selfProxy
    } else {
      selfProxy
    }
}
