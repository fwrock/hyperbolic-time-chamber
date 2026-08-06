package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.gvt.{ GVTEstimationStrategy, LocalVirtualTimeReport, MarginBasedGVTEstimation, TerminationPlateauDetector }
import core.entity.event.control.execution.{ LvtReportEvent, TimeManagerRegisterEvent }
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.types.Tick
import core.util.ManagerConstantsUtil.GLOBAL_TIME_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, CoordinatedShutdown, Props, Terminated }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent, StopSimulationEvent }

import scala.collection.mutable

/** Time Warp's global-time-manager coordinator (`docs/TIME_WARP_DESIGN.md` §2/§3/§11): a GVT
  * coordinator instead of a `SelectiveBarrier`. Every registered `OptimisticLocalTimeManager`
  * reports its local virtual time asynchronously via [[LvtReportEvent]] (never a blocking
  * handshake — see that message's doc); this class aggregates those reports into a GVT estimate
  * via a pluggable [[GVTEstimationStrategy]] and declares termination once [[TerminationPlateauDetector]]
  * says every LTM is idle and the GVT has stopped moving for long enough.
  *
  * Deliberately does not attempt the conservative side's progressive-loading/migration-pause
  * coordination — neither interaction is designed for optimistic mode anywhere in
  * `docs/TIME_WARP_DESIGN.md` (both are explicitly absent from its "Open questions," meaning not
  * even flagged as known-undesigned, just never considered) — adding either now would be a guess.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  * @param gvtEstimationStrategy
  *   how to turn per-LTM LVT reports into one GVT estimate; margin-based is v1's only
  *   implementation (§3) — no default tuned margin, per the design doc's "measure before guessing"
  *   posture, so callers must supply one explicitly
  * @param plateauRoundsRequired
  *   how many consecutive idle-and-unchanged-GVT rounds before declaring termination (§11); same
  *   "measure before guessing" posture
  */
class OptimisticGlobalTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  gvtEstimationStrategy: GVTEstimationStrategy,
  plateauRoundsRequired: Int
) extends GlobalTimeManagerBase(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      actorId = GLOBAL_TIME_MANAGER_ACTOR_NAME
    ) {

  private val registeredManagers: mutable.Set[ActorRef] = mutable.Set.empty
  private val latestReports: mutable.Map[ActorRef, LocalVirtualTimeReport] = mutable.Map.empty
  private val plateauDetector = new TerminationPlateauDetector(plateauRoundsRequired)
  private var currentGvt: Tick = Long.MinValue
  private var simulationStarted: Boolean = false
  @volatile private var isTerminated = false

  override def onStart(): Unit = createTimeManagersPool()

  override protected def localTimeManagerProps(): Props =
    OptimisticLocalTimeManager.props(simulationDuration, simulationManager, Some(getSelfProxy))

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case schedule: org.htc.protobuf.core.entity.event.communication.ScheduleEvent => scheduleEvent(schedule)
    case timeManagerRegisterEvent: TimeManagerRegisterEvent =>
      registerTimeManager(timeManagerRegisterEvent)
    case report: LvtReportEvent =>
      handleLvtReport(report)
    case Terminated(ref) =>
      handleTimeManagerTerminated(ref)
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    simulationStarted = true
    logInfo(s"Optimistic GlobalTimeManager started at tick ${event.startTick}")
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    notifyLocalManagers(event)
    startTime = System.currentTimeMillis()
  }

  protected def registerActor(event: org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent): Unit = {}

  protected def scheduleEvent(event: org.htc.protobuf.core.entity.event.communication.ScheduleEvent): Unit =
    timeManagersPool ! event

  protected def finishEvent(event: FinishEvent): Unit = {}

  override protected def pauseSimulation(): Unit = {
    super.pauseSimulation()
    plateauDetector.reset()
    notifyLocalManagers(org.htc.protobuf.core.entity.event.control.execution.PauseSimulationEvent())
  }

  override protected def resumeSimulation(): Unit =
    if (isPaused) {
      isPaused = false
      self ! SpontaneousEvent(tick = localTickOffset, actorRef = self)
      notifyLocalManagers(org.htc.protobuf.core.entity.event.control.execution.ResumeSimulationEvent())
    }

  override protected def stopSimulation(): Unit = {
    super.stopSimulation()
    terminateSimulation()
  }

  private def registerTimeManager(event: TimeManagerRegisterEvent): Unit =
    if (registeredManagers.add(event.actorRef)) {
      context.watch(event.actorRef)
      logInfo(
        s"Registered Optimistic LocalTimeManager: ${event.actorRef.path.name} " +
          s"on ${event.actorRef.path.address} - Total registered: ${registeredManagers.size}"
      )
    }

  private def handleTimeManagerTerminated(ref: ActorRef): Unit =
    if (registeredManagers.remove(ref)) {
      latestReports.remove(ref)
      logWarn(
        s"Optimistic LocalTimeManager terminated: ${ref.path.name} on ${ref.path.address}. " +
          s"Remaining: ${registeredManagers.size}"
      )
      recomputeGvtAndCheckTermination()
    }

  private def handleLvtReport(report: LvtReportEvent): Unit = {
    val manager = sender()
    if (!registeredManagers.contains(manager)) {
      logWarn(s"Received LVT report from unregistered manager: ${manager.path}")
      return
    }
    latestReports.update(manager, LocalVirtualTimeReport(source = manager, lvt = report.lvt, isIdle = report.isIdle))
    recomputeGvtAndCheckTermination()
  }

  private def recomputeGvtAndCheckTermination(): Unit = {
    if (isTerminated || !simulationStarted) return
    // Only estimate once every registered LTM has reported at least once — an incomplete report
    // set makes "all idle" meaningless (an LTM that hasn't reported yet might not be idle) and the
    // GVT strategy's own doc already requires it to under-, not over-, estimate for a partial set,
    // so waiting for completeness here is the more conservative (and simpler) choice for v1.
    if (latestReports.size < registeredManagers.size) return

    currentGvt = gvtEstimationStrategy.estimate(latestReports.values.toSeq)
    val allIdle = latestReports.values.forall(_.isIdle)
    if (plateauDetector.observe(allIdle = allIdle, gvt = currentGvt)) {
      logInfo(s"GVT plateaued at $currentGvt with all LTMs idle for $plateauRoundsRequired rounds — terminating")
      terminateSimulation()
    }
  }

  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {}

  protected def advanceToNextTick(): Unit = {}

  protected def nextTick: Option[Tick] =
    if (localTickOffset - initialTick >= simulationDuration) None else Some(localTickOffset + 1)

  private def terminateSimulation(): Unit = synchronized {
    if (!isTerminated) {
      isTerminated = true
      printSimulationDuration()
      logInfo(s"Optimistic simulation terminated at GVT=$currentGvt")
      notifyLocalManagers(StopSimulationEvent())
      simulationManager ! StopSimulationEvent()
      CoordinatedShutdown(context.system).run(CoordinatedShutdown.JvmExitReason)
    }
  }
}

object OptimisticGlobalTimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    gvtMargin: Tick,
    plateauRoundsRequired: Int
  ): Props =
    Props(
      classOf[OptimisticGlobalTimeManager],
      simulationDuration,
      simulationManager,
      new MarginBasedGVTEstimation(gvtMargin),
      plateauRoundsRequired
    )
}
