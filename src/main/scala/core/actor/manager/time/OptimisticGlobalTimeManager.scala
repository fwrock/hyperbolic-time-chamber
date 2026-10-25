package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.gvt.{ GVTEstimationStrategy, MarginBasedGVTEstimation }
import core.api.SimulatorSettingsRegistry
import core.entity.event.control.execution.{ LvtReportEvent, TimeManagerRegisterEvent }
import core.entity.event.control.load.*
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.runtime.accounting.*
import core.types.Tick
import core.util.ManagerConstantsUtil.{ GLOBAL_TIME_MANAGER_ACTOR_NAME, REPORT_MANAGER_ACTOR_NAME }
import org.apache.pekko.actor.{ ActorRef, Cancellable, Props, Terminated, OneForOneStrategy, SupervisorStrategy }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent, StopSimulationEvent }
import scala.concurrent.duration.*

/** Centralized reference implementation retained for compatibility tests and comparisons.
  * Production `props` selects [[AccountedOptimisticGlobalTimeManager]], whose LTMs own transport.
  */
class OptimisticGlobalTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  gvtEstimationStrategy: GVTEstimationStrategy,
  plateauRoundsRequired: Int
) extends GlobalTimeManagerBase(simulationDuration, simulationManager, GLOBAL_TIME_MANAGER_ACTOR_NAME) {
  protected var accountedCoordinator: ActorRef = _
  override protected def runtimeCoordinatorForActors: Option[ActorRef] = Option(accountedCoordinator)
  private var progressive: Option[ActorRef] = None
  private var inputClosed = true
  private var pendingStart: Option[StartSimulationTimeEvent] = None
  private var started = false
  private var finished = false
  private var heartbeat: Option[Cancellable] = None

  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() { case _: Exception => SupervisorStrategy.Stop }

  override def onStart(): Unit = {
    val managerCount = SimulatorSettingsRegistry.getInt("htc.time-manager.total-instances", config)
    accountedCoordinator = context.actorOf(AccountedRuntimeCoordinator.props(
      managerCount, simulationDuration, Some(createSingletonProxy(REPORT_MANAGER_ACTOR_NAME))), "accounted-runtime")
    context.watch(accountedCoordinator)
    createTimeManagersPool()
  }

  override protected def localTimeManagerProps(): Props =
    OptimisticLocalTimeManager.props(simulationDuration, simulationManager, Some(getSelfProxy), Option(accountedCoordinator))

  override def handleEvent: Receive = {
    case event: StartSimulationTimeEvent => startSimulation(event)
    case event: RegisterProgressiveLoadManagerEvent =>
      progressive = Some(event.progressiveLoadManager)
      inputClosed = false
      event.ackTo.foreach(_ ! ProgressiveLoadManagerRegisteredEvent(event.lookAheadTicks))
    case event: TickWindowReady =>
      if (event.readyUpToTick == Long.MaxValue) { inputClosed = true; beginExecution() }
      else if (pendingStart.nonEmpty) progressive.foreach(_ ! TickWindowRequest(event.readyUpToTick + 1L, Long.MaxValue))
    case _: ProgressiveLoadingCompleteEvent => inputClosed = true; beginExecution()
    case _: LvtReportEvent => () // Diagnostic LVT numbers never authorize commitment.
    case _: TimeManagerRegisterEvent => ()
    case RuntimeExecutionPaused(round) => logInfo(s"Accounted runtime paused after round=$round")
    case RequestRuntimeRound() => if (started && !finished) accountedCoordinator ! RequestRuntimeRound()
    case RuntimeAdvanced(round, gvt) if sender() == accountedCoordinator =>
      localTickOffset = gvt
      logInfo(s"[ACCOUNTED-GVT] round=$round frontier=$gvt")
    case RuntimeFinished(gvt, truncated) if sender() == accountedCoordinator =>
      finished = true
      heartbeat.foreach(_.cancel())
      logInfo(s"[ACCOUNTED-COMPLETE] frontier=$gvt status=${if (truncated) "horizon-truncated" else "completed"}")
      simulationManager ! StopSimulationEvent()
    case Terminated(ref) if ref == accountedCoordinator && !finished =>
      finished = true
      heartbeat.foreach(_.cancel())
      simulationManager ! RuntimeFailure("accounted coordinator lost; run cannot recover independently")
    case failure: RuntimeFailure =>
      finished = true
      heartbeat.foreach(_.cancel())
      logError(s"[RUNTIME-FAILED] ${failure.reason}")
      simulationManager ! failure
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    pendingStart = Some(event)
    initialTick = event.startTick
    if (!inputClosed) progressive.foreach(_ ! TickWindowRequest(event.startTick, Long.MaxValue))
    else beginExecution()
  }

  private def beginExecution(): Unit = if (!finished && !started && inputClosed && pendingStart.nonEmpty) {
    require(accountedCoordinator != null, "optimistic execution requires an accounted runtime")
    val event = pendingStart.get
    pendingStart = None
    started = true
    startTime = System.currentTimeMillis()
    accountedCoordinator ! StartRuntime(event.startTick)
    val interval = SimulatorSettingsRegistry.getInt("htc.time-warp.termination-heartbeat-interval-ms", config).millis
    heartbeat = Some(context.system.scheduler.scheduleWithFixedDelay(interval, interval, self,
      RequestRuntimeRound())(context.dispatcher))
  }

  override protected def pauseSimulation(): Unit = {
    isPaused = true
    Option(accountedCoordinator).foreach(_ ! PauseRuntimeExecution())
  }
  override protected def resumeSimulation(): Unit = {
    isPaused = false
    Option(accountedCoordinator).foreach(_ ! ResumeRuntimeExecution())
  }

  override def postStop(): Unit = { heartbeat.foreach(_.cancel()); super.postStop() }
  override protected def stopSimulation(): Unit =
    Option(accountedCoordinator).foreach(_ ! RuntimeFailure("simulation cancelled externally"))
  def onProgressiveLoadingComplete(): Unit = { inputClosed = true; beginExecution() }
  protected def registerActor(event: org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent): Unit = ()
  protected def scheduleEvent(event: ScheduleEvent): Unit = throw new IllegalStateException("unowned optimistic schedule")
  protected def finishEvent(event: FinishEvent): Unit = ()
  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = ()
  protected def advanceToNextTick(): Unit = ()
  protected def nextTick: Option[Tick] = None
}

object OptimisticGlobalTimeManager {
  def props(simulationDuration: Tick, simulationManager: ActorRef, gvtMargin: Tick, plateauRoundsRequired: Int): Props =
    Props(new AccountedOptimisticGlobalTimeManager(simulationDuration, simulationManager))
}
