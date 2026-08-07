package org.interscity.htc
package core.actor.manager.time

import core.actor.manager.time.gvt.{ GVTEstimationStrategy, LocalVirtualTimeReport, MarginBasedGVTEstimation, TerminationPlateauDetector }
import core.api.SimulatorSettingsRegistry
import core.entity.event.control.execution.{ GvtUpdateEvent, LvtReportEvent, TimeManagerRegisterEvent }
import core.entity.event.control.load.{ ProgressiveLoadManagerRegisteredEvent, ProgressiveLoadingCompleteEvent, RegisterProgressiveLoadManagerEvent, TickWindowReady, TickWindowRequest }
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.types.Tick
import core.util.ManagerConstantsUtil.GLOBAL_TIME_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, Cancellable, CoordinatedShutdown, Props, Terminated }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent, StopSimulationEvent }

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

/** Time Warp's global-time-manager coordinator (`docs/TIME_WARP_DESIGN.md` §2/§3/§11): a GVT
  * coordinator instead of a `SelectiveBarrier`. Every registered `OptimisticLocalTimeManager`
  * reports its local virtual time asynchronously via [[LvtReportEvent]] (never a blocking
  * handshake — see that message's doc); this class aggregates those reports into a GVT estimate
  * via a pluggable [[GVTEstimationStrategy]] and declares termination once [[TerminationPlateauDetector]]
  * says every LTM is idle and the GVT has stopped moving for long enough.
  *
  * Handles progressive loading (see [[handleTickWindowReady]]/[[recomputeGvtAndCheckTermination]])
  * since `SimulationManager` sends `RegisterProgressiveLoadManagerEvent` unconditionally whenever a
  * scenario has progressive sources, regardless of which GTM is active — without an ack, a
  * Time Warp run would hang forever waiting for one, and without gating termination on it, an
  * "idle GVT plateau" caused by nothing having been progressively loaded yet would be
  * indistinguishable from real completion. Still does not attempt migration-pause coordination —
  * that interaction genuinely isn't designed for optimistic mode anywhere in
  * `docs/TIME_WARP_DESIGN.md`, and shard rebalancing is disabled project-wide regardless (see
  * `CLAUDE.md`).
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

  private var progressiveLoadManager: ActorRef = _
  private var progressiveLoadingEnabled: Boolean = false
  private var progressiveLoadingComplete: Boolean = false
  private var progressiveLoadedUpToTick: Tick = Long.MaxValue
  private var maxLookAheadTicks: Tick = 10_000L
  private var waitingForInitialWindow: Boolean = false
  private var waitingForNextWindow: Boolean = false
  private var pendingStartEvent: Option[StartSimulationTimeEvent] = None

  /** Self-scheduled heartbeat, started once the simulation actually begins dispatching (see
    * [[startHeartbeat]]'s doc for why this exists at all — `recomputeGvtAndCheckTermination`
    * otherwise only runs reactively off `LvtReportEvent`, which stops arriving entirely once
    * everything goes idle, before `TerminationPlateauDetector` ever accumulates enough consecutive
    * rounds to declare termination).
    */
  private var heartbeatCancellable: Option[Cancellable] = None

  override def onStart(): Unit = createTimeManagersPool()

  /** Found running a real end-to-end scenario under Time Warp (docs/TIME_WARP_DESIGN.md):
    * `recomputeGvtAndCheckTermination` only ever runs reactively, triggered by a new
    * `LvtReportEvent`/`TickWindowReady`/`Terminated` arriving. Once every `OptimisticLocalTimeManager`
    * genuinely goes idle (all real work finished), nothing ever sends another message that would
    * trigger a fresh recompute — so `TerminationPlateauDetector`'s `plateau-rounds-required`
    * consecutive rounds can never accumulate past the single round the *last* real report already
    * produced. The simulation sits fully idle forever, never terminating, with no error and no log.
    * Conservative mode never had this gap: `ConservativeGlobalTimeManager`'s own barrier round is
    * inherently periodic (`UpdateGlobalTimeEvent`/`QueryNextTickEvent` keep cycling regardless of
    * activity). Optimistic mode has no equivalent built-in cadence, so this timer supplies one —
    * self-scheduled, not tied to any particular LTM, cancelled the moment the simulation actually
    * terminates (or this actor stops) so it can never fire after `CoordinatedShutdown` begins.
    */
  private def startHeartbeat(): Unit =
    if (heartbeatCancellable.isEmpty) {
      val interval =
        SimulatorSettingsRegistry.getInt("htc.time-warp.termination-heartbeat-interval-ms", context.system.settings.config).millis
      heartbeatCancellable = Some(
        context.system.scheduler.scheduleWithFixedDelay(interval, interval, self, OptimisticGlobalTimeManager.TerminationHeartbeatTick)(context.dispatcher)
      )
    }

  private def stopHeartbeat(): Unit = {
    heartbeatCancellable.foreach(_.cancel())
    heartbeatCancellable = None
  }

  override def postStop(): Unit = {
    stopHeartbeat()
    super.postStop()
  }

  override protected def localTimeManagerProps(): Props =
    OptimisticLocalTimeManager.props(simulationDuration, simulationManager, Some(getSelfProxy))

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case schedule: org.htc.protobuf.core.entity.event.communication.ScheduleEvent => scheduleEvent(schedule)
    case timeManagerRegisterEvent: TimeManagerRegisterEvent =>
      registerTimeManager(timeManagerRegisterEvent)
    case report: LvtReportEvent =>
      handleLvtReport(report)
    case event: RegisterProgressiveLoadManagerEvent =>
      handleRegisterProgressiveLoadManager(event)
    case event: TickWindowReady =>
      handleTickWindowReady(event)
    case _: ProgressiveLoadingCompleteEvent =>
      onProgressiveLoadingComplete()
    case Terminated(ref) =>
      handleTimeManagerTerminated(ref)
    case OptimisticGlobalTimeManager.TerminationHeartbeatTick =>
      recomputeGvtAndCheckTermination()
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Optimistic GlobalTimeManager started at tick ${event.startTick}")
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false

    if (progressiveLoadingEnabled && !progressiveLoadingComplete) {
      logInfo(s"Progressive loading enabled — holding simulation start until initial window from tick $initialTick is loaded")
      pendingStartEvent = Some(event)
      waitingForInitialWindow = true
      requestProgressiveLoad(initialTick)
      return
    }

    simulationStarted = true
    notifyLocalManagers(event)
    startTime = System.currentTimeMillis()
    startHeartbeat()
  }

  private def handleRegisterProgressiveLoadManager(event: RegisterProgressiveLoadManagerEvent): Unit = {
    progressiveLoadManager = event.progressiveLoadManager
    progressiveLoadingEnabled = true
    progressiveLoadedUpToTick = -1L
    progressiveLoadingComplete = false
    maxLookAheadTicks = event.lookAheadTicks
    logInfo(s"Progressive load manager registered with maxLookAhead=${event.lookAheadTicks}")
    event.ackTo.foreach(_ ! ProgressiveLoadManagerRegisteredEvent(event.lookAheadTicks))
  }

  private def requestProgressiveLoad(fromTick: Tick): Unit =
    if (progressiveLoadManager != null) {
      progressiveLoadManager ! TickWindowRequest(currentTick = fromTick, horizonTick = fromTick + maxLookAheadTicks)
    }

  private def handleTickWindowReady(event: TickWindowReady): Unit = {
    progressiveLoadedUpToTick = event.readyUpToTick
    if (event.readyUpToTick == Long.MaxValue) {
      onProgressiveLoadingComplete()
    }

    if (waitingForInitialWindow) {
      waitingForInitialWindow = false
      logInfo(s"Initial progressive window loaded up to tick ${event.readyUpToTick} (${event.actorsCreated} actors) — starting simulation")
      pendingStartEvent.foreach {
        startEvent =>
          pendingStartEvent = None
          simulationStarted = true
          startTime = System.currentTimeMillis()
          notifyLocalManagers(startEvent)
          startHeartbeat()
      }
      return
    }

    if (waitingForNextWindow) {
      waitingForNextWindow = false
      plateauDetector.reset()
      recomputeGvtAndCheckTermination()
    }
  }

  def onProgressiveLoadingComplete(): Unit = {
    progressiveLoadingComplete = true
    progressiveLoadedUpToTick = Long.MaxValue
    logInfo("Progressive loading complete")
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

  /** Only estimates once every registered LTM has reported at least once — an incomplete report
    * set makes "all idle" meaningless, and the GVT strategy's own doc already requires it to
    * under-, not over-, estimate for a partial set, so waiting for completeness here is the more
    * conservative (and simpler) choice for v1. Also holds off entirely while a progressive window
    * request is in flight ([[waitingForNextWindow]]) or [[isTerminated]]/`!simulationStarted`.
    *
    * An all-idle round while progressive loading isn't complete yet isn't real termination — it
    * usually just means nothing has been created past the current window — so it requests the next
    * window and resets the plateau detector instead of treating idleness as progress toward it.
    */
  private def recomputeGvtAndCheckTermination(): Unit = {
    if (isTerminated || !simulationStarted || waitingForNextWindow) return
    if (latestReports.size < registeredManagers.size) return

    val previousGvt = currentGvt
    currentGvt = gvtEstimationStrategy.estimate(latestReports.values.toSeq)
    // Broadcast only on real advancement (docs/TIME_WARP_DESIGN.md §4): recomputeGvtAndCheckTermination
    // runs on every LvtReportEvent, and an unchanged GVT unlocks nothing new for any LTM to flush —
    // broadcasting every round regardless would multiply this already-per-report-triggered fan-out
    // by the LTM pool size for no benefit, the same "cheap and non-blocking, not free" posture §3
    // already applies to LVT reporting itself.
    if (currentGvt > previousGvt) {
      notifyLocalManagers(GvtUpdateEvent(currentGvt))
    }
    val allIdle = latestReports.values.forall(_.isIdle)

    if (allIdle && progressiveLoadingEnabled && !progressiveLoadingComplete) {
      logInfo(s"All LTMs idle but progressive loading not complete (loadedUpTo=$progressiveLoadedUpToTick) — requesting next window")
      waitingForNextWindow = true
      requestProgressiveLoad(progressiveLoadedUpToTick + 1)
      plateauDetector.reset()
      return
    }

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
      stopHeartbeat()
      printSimulationDuration()
      logInfo(s"Optimistic simulation terminated at GVT=$currentGvt")
      notifyLocalManagers(StopSimulationEvent())
      simulationManager ! StopSimulationEvent()
      CoordinatedShutdown(context.system).run(CoordinatedShutdown.JvmExitReason)
    }
  }
}

object OptimisticGlobalTimeManager {

  /** Self-sent by [[OptimisticGlobalTimeManager.startHeartbeat]]'s periodic scheduler — see that
    * method's doc for why this exists. Not a public protocol message; only this actor ever sends
    * or handles it.
    */
  private case object TerminationHeartbeatTick

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
