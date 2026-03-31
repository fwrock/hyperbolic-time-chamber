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
import org.interscity.htc.core.entity.event.control.loadbalance.{ MigrationCompleteNotifyEvent, MigrationSafeEvent, RequestMigrationPauseEvent }
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

  /** When true, the GlobalTimeManager will not advance to the next tick.
    * Set by RequestMigrationPauseEvent from LoadBalanceManager, cleared by MigrationCompleteNotifyEvent.
    */
  private var migrationPauseRequested: Boolean = false

  /** Reference to the LoadBalanceManager that requested the pause, for sending MigrationSafeEvent. */
  private var migrationRequester: ActorRef = _

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
    case migrationPause: RequestMigrationPauseEvent =>
      handleMigrationPauseRequest(migrationPause)
    case _: MigrationCompleteNotifyEvent =>
      handleMigrationComplete()
    case event => super.handleEvent(event)
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
