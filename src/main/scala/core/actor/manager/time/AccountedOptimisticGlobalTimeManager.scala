package org.interscity.htc
package core.actor.manager.time

import core.api.SimulatorSettingsRegistry
import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.entity.event.control.execution.{LvtReportEvent, TimeManagerRegisterEvent}
import core.entity.event.control.load.*
import core.runtime.accounting.*
import core.runtime.distributed.*
import core.types.Tick
import core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, REPORT_MANAGER_ACTOR_NAME}
import org.apache.pekko.actor.{ActorRef, Cancellable, Props, Terminated}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{StartSimulationTimeEvent, StopSimulationEvent}
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Only membership and round summaries reach this singleton; model traffic stays with LTMs. */
class AccountedOptimisticGlobalTimeManager(duration: Tick, manager: ActorRef)
  extends GlobalTimeManagerBase(duration, manager, GLOBAL_TIME_MANAGER_ACTOR_NAME) {
  private enum Phase { case Loading, Running, Freezing, Collecting, Committing, Retiring, Paused, Finished, Failed }
  import Phase.*
  private var phase = Loading
  private val epoch = java.util.UUID.randomUUID().toString
  private val workers = mutable.Set.empty[ActorRef]
  private val ownership = mutable.Map.empty[LogicalProcessId, ActorRef]
  private val waitingJoins = mutable.ArrayBuffer.empty[JoinRuntime]
  private val registrations = mutable.Set.empty[LogicalProcessId]
  private var pendingStart: Option[StartSimulationTimeEvent] = None
  private var producer: Option[ActorRef] = None
  private var inputClosed = true
  private var started = false
  private var horizon = Long.MaxValue - 1L
  private var roundId = 0L
  private var round: Option[QuiescentRound] = None
  private var pending = Set.empty[ActorRef]
  private var gvt = Long.MinValue
  private var finalCut = false
  private var truncated = false
  private var sinks = Set.empty[ActorRef]
  private var flushed = false
  private var heartbeat: Option[Cancellable] = None
  private var rounds = 0L
  private var phaseStartedAt = 0L
  private var freezeNanos = 0L
  private var snapshotNanos = 0L
  private var commitNanos = 0L

  protected def expectedWorkers: Int = SimulatorSettingsRegistry.getInt("htc.time-manager.total-instances", config)
  protected lazy val reportManagerRef: ActorRef = createSingletonProxy(REPORT_MANAGER_ACTOR_NAME)
  override protected def runtimeCoordinatorForActors: Option[ActorRef] = Some(self)
  override protected def localTimeManagerProps(): Props = AccountedOptimisticLocalTimeManager.props(duration, manager, self)
  override def onStart(): Unit = createTimeManagersPool()
  override def postStop(): Unit = { heartbeat.foreach(_.cancel()); super.postStop() }
  private def key(ref: ActorRef): String = ref.path.toStringWithAddress(self.path.address)
  private def fail(error: Throwable): Unit = if (phase != Failed && phase != Finished) {
    phase = Failed
    heartbeat.foreach(_.cancel())
    workers.foreach(_ ! RuntimeFailure(error.getMessage))
    logError(s"[RUNTIME-FAILED] ${error.getMessage}")
    manager ! RuntimeFailure(error.getMessage)
  }
  private def protect(body: => Unit): Unit = try body catch { case NonFatal(e) => fail(e) }
  private def join(event: JoinRuntime): Unit = {
    require(phase == Loading, "optimistic LP registration after input closure")
    val id = LogicalProcessId(event.identity.classType, event.identity.id)
    require(!ownership.contains(id), s"duplicate LP $id")
    val members = workers.toVector.sortBy(_.path.toString)
    val owner = members(Math.floorMod(id.hashCode(), members.size))
    ownership(id) = owner
    registrations.add(id)
    owner ! RegisterLocalProcess(epoch, event)
  }

  override def handleEvent: Receive = {
    case _ if phase == Failed || phase == Finished => ()
    case JoinRuntimeManager(ref) => protect {
      require(sender() == ref && phase == Loading && !workers.contains(ref), "invalid LTM membership")
      workers.add(ref)
      context.watch(ref)
      require(workers.size <= expectedWorkers, "unexpected LTM")
      if (workers.size == expectedWorkers) {
        val membership = RuntimeMembership(epoch, workers.toVector.sortBy(_.path.toString))
        workers.foreach(_ ! membership)
        waitingJoins.foreach(join)
        waitingJoins.clear()
        maybeStart()
      }
    }
    case event: JoinRuntime => protect {
      require(sender() == event.ref && phase == Loading, "invalid LP enrollment")
      if (workers.size < expectedWorkers) waitingJoins += event else join(event)
    }
    case LocalProcessRegistered(id) => protect {
      require(ownership.get(id).contains(sender()) && registrations.remove(id), "unknown registration acknowledgement")
      maybeStart()
    }
    case event: RegisterProgressiveLoadManagerEvent => protect {
      require(phase == Loading && producer.isEmpty && pendingStart.isEmpty, "late input producer registration")
      producer = Some(event.progressiveLoadManager)
      inputClosed = false
      event.ackTo.foreach(_ ! ProgressiveLoadManagerRegisteredEvent(event.lookAheadTicks))
    }
    case event: TickWindowReady => protect {
      if (event.readyUpToTick == Long.MaxValue) { inputClosed = true; maybeStart() }
      else if (!inputClosed && pendingStart.nonEmpty)
        producer.foreach(_ ! TickWindowRequest(Math.addExact(event.readyUpToTick, 1L), Long.MaxValue))
    }
    case _: ProgressiveLoadingCompleteEvent => inputClosed = true; maybeStart()
    case event: StartSimulationTimeEvent => startSimulation(event)
    case RequestRuntimeRound() if phase == Running => beginRound()
    case RuntimeFrozen(id) => protect {
      require(workers.contains(sender()), "freeze acknowledgement from non-worker")
      if (phase == Freezing && id == roundId) {
        round.get.acknowledgeFrozen(key(sender()), id)
        if (round.get.allFrozen) {
          freezeNanos = System.nanoTime() - phaseStartedAt
          phaseStartedAt = System.nanoTime()
          phase = Collecting
          workers.foreach(_ ! CollectRuntimeSnapshot(roundId))
        }
      }
    }
    case snapshot: RuntimeSnapshot => protect {
      require(workers.contains(sender()), "snapshot from non-worker")
      if (phase == Collecting && snapshot.round == roundId) {
        round.get.report(key(sender()), snapshot)
        if (round.get.complete) commitCut()
      }
    }
    case LocalRuntimeCommitted(id, targets) => protect {
      require(workers.contains(sender()), "commit from non-worker")
      if (phase == Committing && id == roundId && pending.contains(sender())) {
        pending -= sender()
        sinks ++= targets
        if (pending.isEmpty) {
          commitNanos = System.nanoTime() - phaseStartedAt
          phaseStartedAt = System.nanoTime()
          phase = Retiring
          pending = workers.toSet
          flushed = !finalCut
          workers.foreach(_ ! RetireLocalProcesses(roundId, finalCut))
          if (finalCut) reportManagerRef ! DrainRuntimeReports(roundId, self, sinks)
        }
      }
    }
    case LocalProcessesRetired(id) => protect {
      require(workers.contains(sender()), "retirement from non-worker")
      if (phase == Retiring && id == roundId) { pending -= sender(); afterRetire() }
    }
    case RuntimeReportsFlushed(id, error) if phase == Retiring && id == roundId => protect {
      error.foreach(reason => throw new IllegalStateException(reason))
      flushed = true
      afterRetire()
    }
    case RuntimeFailure(reason) => fail(new IllegalStateException(reason))
    case Terminated(ref) if workers.contains(ref) => fail(new IllegalStateException(s"runtime LTM lost: $ref"))
    case _: TimeManagerRegisterEvent | _: LvtReportEvent | _: RequestRuntimeRound => ()
    case _: RouteInteraction | _: RouteControl | _: RouteDispatch | _: PublishCommittedReport =>
      fail(new IllegalStateException("per-message traffic reached the GTM instead of its owning LTM"))
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = protect {
    require(phase == Loading && pendingStart.isEmpty, "duplicate optimistic start")
    pendingStart = Some(event)
    initialTick = event.startTick
    if (!inputClosed) producer.foreach(_ ! TickWindowRequest(event.startTick, Long.MaxValue))
    maybeStart()
  }
  private def maybeStart(): Unit = if (!started && pendingStart.nonEmpty && inputClosed &&
    workers.size == expectedWorkers && registrations.isEmpty && waitingJoins.isEmpty) {
    horizon = Math.addExact(initialTick, duration)
    require(horizon < Long.MaxValue, "finite horizon required")
    started = true
    phase = Running
    startTime = System.currentTimeMillis()
    pendingStart = None
    workers.foreach(_ ! StartLocalRuntime(initialTick, horizon))
    val interval = SimulatorSettingsRegistry.getInt("htc.time-warp.termination-heartbeat-interval-ms", config).millis
    heartbeat = Some(context.system.scheduler.scheduleWithFixedDelay(interval, interval, self, RequestRuntimeRound())(context.dispatcher))
    if (isPaused) beginRound()
  }
  private def beginRound(): Unit = {
    roundId = Math.addExact(roundId, 1L)
    rounds += 1L
    round = Some(new QuiescentRound(roundId, workers.map(key).toSet))
    phase = Freezing
    phaseStartedAt = System.nanoTime()
    workers.foreach(_ ! FreezeRuntime(roundId))
  }
  private def commitCut(): Unit = {
    snapshotNanos = System.nanoTime() - phaseStartedAt
    val cut = round.get
    require(cut.callbacks == 0 || cut.minimum.nonEmpty, "unbounded asynchronous callback")
    require(cut.blocked == 0 || cut.minimum.nonEmpty, "unbounded reply obligation")
    truncated = cut.minimum.exists(_ > horizon)
    finalCut = (cut.minimum.isEmpty && cut.blocked == 0 && cut.callbacks == 0) || truncated
    val candidate = if (truncated) Math.addExact(horizon, 1L) else cut.minimum.getOrElse(Long.MaxValue)
    require(candidate >= gvt, s"GVT regression: $gvt -> $candidate")
    gvt = candidate
    localTickOffset = gvt
    logInfo(s"[ACCOUNTED-GVT] round=$roundId frontier=$gvt")
    phase = Committing
    phaseStartedAt = System.nanoTime()
    pending = workers.toSet
    workers.foreach(_ ! CommitRuntime(roundId, gvt))
  }
  private def afterRetire(): Unit = if (phase == Retiring && pending.isEmpty && flushed) {
    val retireNanos = System.nanoTime() - phaseStartedAt
    logInfo(s"[ACCOUNTED-ROUND] round=$roundId freezeNanos=$freezeNanos snapshotNanos=$snapshotNanos commitNanos=$commitNanos retireNanos=$retireNanos")
    if (finalCut) {
      phase = Finished
      heartbeat.foreach(_.cancel())
      logInfo(s"[ACCOUNTED-COMPLETE] frontier=$gvt status=${if (truncated) "horizon-truncated" else "completed"} coordinator=gtm workers=${workers.size} rounds=$rounds")
      manager ! StopSimulationEvent()
    } else if (isPaused) phase = Paused
    else { phase = Running; workers.foreach(_ ! ResumeRuntime(roundId)) }
  }
  override protected def pauseSimulation(): Unit = { isPaused = true; if (phase == Running) beginRound() }
  override protected def resumeSimulation(): Unit = {
    isPaused = false
    if (phase == Paused) { phase = Running; workers.foreach(_ ! ResumeRuntime(roundId)) }
  }
  override protected def stopSimulation(): Unit = fail(new IllegalStateException("simulation cancelled externally"))
  protected def registerActor(event: org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent): Unit = ()
  protected def scheduleEvent(event: ScheduleEvent): Unit = throw new IllegalStateException("unowned optimistic schedule")
  protected def finishEvent(event: FinishEvent): Unit = ()
  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = ()
  protected def advanceToNextTick(): Unit = ()
  protected def nextTick: Option[Tick] = None
}
