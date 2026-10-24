package org.interscity.htc
package core.actor.manager.time

import core.api.SimulatorSettingsRegistry
import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.entity.event.control.execution.{LvtReportEvent, TimeManagerRegisterEvent}
import core.entity.event.control.load.*
import core.runtime.accounting.*
import core.runtime.distributed.*
import core.types.Tick
import core.runtime.OptimisticExecutionLimits
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
  private enum Phase { case Loading, OpeningInput, Enrolling, ClosingInput, Running, Freezing, Collecting, Committing, Retiring, Paused, Finished, Failed }
  import Phase.*
  private var phase = Loading
  private val epoch = java.util.UUID.randomUUID().toString
  private val workers = mutable.Set.empty[ActorRef]
  private val ownership = mutable.Map.empty[LogicalProcessId, ActorRef]
  private val waitingJoins = mutable.ArrayBuffer.empty[JoinRuntime]
  private val registrations = mutable.Set.empty[LogicalProcessId]
  private val birthReservations = mutable.Map.empty[LogicalProcessId, RuntimeBirthOrigin]
  private var pendingStart: Option[StartSimulationTimeEvent] = None
  private var producer: Option[ActorRef] = None
  private var inputClosed = true
  private var loadedThrough = Long.MaxValue
  private var lookAhead = 1000L
  private var inputWindow = 0L
  private var inputRequest: Option[LoadOptimisticInputWindow] = None
  private var inputReady: Option[OptimisticInputWindowReady] = None
  private var producerActor: Option[ActorRef] = None
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
  private var retentionReports = Map.empty[ActorRef, RuntimeRetention]
  protected lazy val executionLimits: OptimisticExecutionLimits = OptimisticExecutionLimits.fromConfig(context.system.settings.config)
  protected def recordRetention(round: Long, retained: RuntimeRetention): Unit =
    logInfo(s"[ACCOUNTED-RETENTION] round=$round checkpoints=${retained.checkpoints} events=${retained.events} reads=${retained.reads} processedIds=${retained.processedIds} cancellations=${retained.cancellations} reports=${retained.reports} speculativeEvents=${retained.speculativeEvents}")

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
    val id = LogicalProcessId(event.identity.classType, event.identity.id)
    val birth = event.birth.exists(origin => birthReservations.get(id).contains(origin))
    require(phase == Loading || phase == Enrolling || (birth && (phase == Running || phase == Freezing)),
      "optimistic LP registration outside input enrollment")
    if (birth) require(event.initialWake.isEmpty && event.startTick == event.birth.get.tick && event.startTick >= gvt,
      "invalid inert child enrollment")
    else if (started) {
      val request = inputRequest.getOrElse(throw new IllegalStateException("LP enrollment without input request"))
      require(event.startTick >= request.fromTick && event.startTick <= request.throughTick &&
        event.startTick >= gvt && event.initialWake.forall(_.tick >= gvt), "new LP outside input window/below GVT")
    }
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
      require(sender() == event.ref, "invalid LP enrollment")
      if (workers.size < expectedWorkers) waitingJoins += event else join(event)
    }
    case RequestRuntimeBirth(run, target, origin) => protect {
      require(run == epoch && (phase == Running || phase == Freezing), "birth outside execution/draining")
      val members = workers.toVector.sortBy(_.path.toString)
      require(members(Math.floorMod(target.hashCode(), members.size)) == sender() &&
        ownership.contains(origin.parent) && origin.tick >= gvt, "invalid birth authority/frontier")
      require(!ownership.contains(target) && !birthReservations.contains(target), "duplicate birth reservation")
      birthReservations(target) = origin
      sender() ! AuthorizeRuntimeBirth(epoch, target, origin)
    }
    case LocalProcessRegistered(id) => protect {
      require(ownership.get(id).contains(sender()) && registrations.remove(id), "unknown registration acknowledgement")
      maybeStart()
      maybeCompleteInput()
    }
    case event: RegisterProgressiveLoadManagerEvent => protect {
      require(phase == Loading && producer.isEmpty && pendingStart.isEmpty, "late input producer registration")
      producer = Some(event.progressiveLoadManager)
      inputClosed = false
      loadedThrough = Long.MinValue
      require(event.lookAheadTicks > 0L, "input look-ahead must be positive")
      val configuredWindow = SimulatorSettingsRegistry.get("htc.time-warp.input-window-ticks")
        .fold(context.system.settings.config.getLong("htc.time-warp.input-window-ticks"))(_.toLong)
      require(configuredWindow > 0L, "optimistic input window must be positive")
      lookAhead = math.min(event.lookAheadTicks, configuredWindow)
      event.ackTo.foreach(_ ! ProgressiveLoadManagerRegisteredEvent(event.lookAheadTicks))
    }
    // SimulationManager forwards legacy EOF through a different sender. Only the correlated
    // producer reply may close optimistic input, after the last initialization acknowledgement.
    case _: TickWindowReady | _: ProgressiveLoadingCompleteEvent => ()
    case OptimisticInputWindowOpened(run, window) => protect {
      require(inputRequest.exists(r => r.epoch == run && r.window == window) &&
        producerActor.forall(_ == sender()), "stale/foreign input producer handshake")
      if (producerActor.isEmpty) { producerActor = Some(sender()); context.watch(sender()) }
    }
    case event: OptimisticInputWindowReady => protect {
      val request = inputRequest.getOrElse(throw new IllegalStateException("unsolicited input window completion"))
      require((phase == Loading || phase == Enrolling) && event.epoch == epoch &&
        event.window == request.window && inputReady.isEmpty, "stale/duplicate input window completion")
      require(producerActor.contains(sender()), "input producer did not establish its actor identity")
      require(event.loadedThrough >= request.fromTick && event.loadedThrough <= request.throughTick &&
        event.actorsCreated >= 0L, "invalid input coverage")
      inputReady = Some(event)
      maybeCompleteInput()
    }
    case InputEnrollmentOpened(id) => protect {
      require(phase == OpeningInput && id == roundId && pending.contains(sender()), "unexpected enrollment-open acknowledgement")
      pending -= sender()
      if (pending.isEmpty) { phase = Enrolling; requestInput(Math.addExact(loadedThrough, 1L)) }
    }
    case InputEnrollmentCompleted(id) => protect {
      require(phase == ClosingInput && id == roundId && pending.contains(sender()), "unexpected enrollment-close acknowledgement")
      pending -= sender()
      if (pending.isEmpty) resumeOrPause()
    }
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
          retentionReports = Map.empty
          workers.foreach(_ ! CollectRuntimeSnapshot(roundId))
        }
      }
    }
    case snapshot: RuntimeSnapshot => protect {
      require(workers.contains(sender()), "snapshot from non-worker")
      if (phase == Collecting && snapshot.round == roundId) {
        // Each LTM reports once per collection; duplicate summaries must not inflate metrics.
        snapshot.retention.foreach(value => retentionReports += sender() -> value)
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
    case Terminated(ref) if producerActor.contains(ref) && !inputClosed => fail(new IllegalStateException("input producer lost before closure"))
    case _: TimeManagerRegisterEvent | _: LvtReportEvent | _: RequestRuntimeRound => ()
    case _: RouteInteraction | _: RouteControl | _: RouteDispatch | _: PublishCommittedReport =>
      fail(new IllegalStateException("per-message traffic reached the GTM instead of its owning LTM"))
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = protect {
    require(phase == Loading && pendingStart.isEmpty, "duplicate optimistic start")
    pendingStart = Some(event)
    initialTick = event.startTick
    horizon = Math.addExact(initialTick, duration)
    require(horizon < Long.MaxValue, "finite horizon required")
    if (!inputClosed) requestInput(0L)
    maybeStart()
  }
  private def maybeStart(): Unit = if (!started && pendingStart.nonEmpty && (inputClosed || loadedThrough >= initialTick) &&
    workers.size == expectedWorkers && registrations.isEmpty && waitingJoins.isEmpty) {
    horizon = Math.addExact(initialTick, duration)
    require(horizon < Long.MaxValue, "finite horizon required")
    started = true
    phase = Running
    startTime = System.currentTimeMillis()
    pendingStart = None
    logInfo(s"[OPTIMISTIC-LIMITS] maxAheadTicks=${executionLimits.maxAheadTicks} maxUncommittedEvents=${executionLimits.maxUncommittedEvents} maxEventsPerTurn=${executionLimits.maxEventsPerTurn} fossilCollection=${executionLimits.fossilCollection}")
    workers.foreach(_ ! StartLocalRuntime(initialTick, horizon, executionLimits, loadedThrough))
    val interval = SimulatorSettingsRegistry.getInt("htc.time-warp.termination-heartbeat-interval-ms", context.system.settings.config).millis
    heartbeat = Some(context.system.scheduler.scheduleWithFixedDelay(interval, interval, self, RequestRuntimeRound())(context.dispatcher))
    if (isPaused) beginRound()
  }
  private def requestInput(from: Long): Unit = {
    require(producer.nonEmpty && inputRequest.isEmpty && from <= horizon, "invalid/overlapping input request")
    inputWindow = Math.addExact(inputWindow, 1L)
    val through = math.min(horizon, if (from > Long.MaxValue - lookAhead) Long.MaxValue else from + lookAhead)
    val request = LoadOptimisticInputWindow(epoch, inputWindow, from, through)
    inputRequest = Some(request)
    producer.get ! request
  }
  private def maybeCompleteInput(): Unit = if (inputReady.nonEmpty && registrations.isEmpty && waitingJoins.isEmpty) {
    val ready = inputReady.get
    loadedThrough = if (ready.closed) Long.MaxValue else ready.loadedThrough
    inputClosed = ready.closed
    inputReady = None
    inputRequest = None
    logInfo(s"[OPTIMISTIC-INPUT] window=${ready.window} loadedThrough=${ready.loadedThrough} closed=$inputClosed actors=${ready.actorsCreated}")
    if (!started) {
      if (!inputClosed && loadedThrough < initialTick) requestInput(Math.addExact(loadedThrough, 1L))
      else maybeStart()
    } else {
      phase = ClosingInput
      pending = workers.toSet
      workers.foreach(_ ! CompleteInputEnrollment(roundId, loadedThrough))
    }
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
    recordRetention(roundId, retentionReports.valuesIterator.foldLeft(RuntimeRetention())(_ + _))
    val minimum = (cut.minimum.toSeq ++ (if (inputClosed) Seq.empty else Seq(Math.addExact(loadedThrough, 1L)))).minOption
    require(cut.callbacks == 0 || minimum.nonEmpty, "unbounded asynchronous callback")
    require(cut.blocked == 0 || minimum.nonEmpty, "unbounded reply obligation")
    truncated = minimum.exists(_ > horizon)
    finalCut = (inputClosed && minimum.isEmpty && cut.blocked == 0 && cut.callbacks == 0) || truncated
    val candidate = if (truncated) Math.addExact(horizon, 1L) else minimum.getOrElse(Long.MaxValue)
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
    } else if (!isPaused && !inputClosed && gvt > loadedThrough) openInput()
    else resumeOrPause()
  }
  private def openInput(): Unit = {
    phase = OpeningInput
    pending = workers.toSet
    workers.foreach(_ ! BeginInputEnrollment(roundId))
  }
  private def resumeOrPause(): Unit = if (isPaused) phase = Paused
    else { phase = Running; workers.foreach(_ ! ResumeRuntime(roundId)) }
  override protected def pauseSimulation(): Unit = { isPaused = true; if (phase == Running) beginRound() }
  override protected def resumeSimulation(): Unit = {
    isPaused = false
    if (phase == Paused) {
      if (!inputClosed && gvt > loadedThrough) openInput() else resumeOrPause()
    }
  }
  override protected def stopSimulation(): Unit = fail(new IllegalStateException("simulation cancelled externally"))
  protected def registerActor(event: org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent): Unit = ()
  protected def scheduleEvent(event: ScheduleEvent): Unit = throw new IllegalStateException("unowned optimistic schedule")
  protected def finishEvent(event: FinishEvent): Unit = ()
  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = ()
  protected def advanceToNextTick(): Unit = ()
  protected def nextTick: Option[Tick] = None
}
