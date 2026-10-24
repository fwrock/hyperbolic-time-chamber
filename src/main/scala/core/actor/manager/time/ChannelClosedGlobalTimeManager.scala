package org.interscity.htc
package core.actor.manager.time

import core.api.SimulatorSettingsRegistry
import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.entity.event.control.execution.TimeManagerRegisterEvent
import core.entity.event.control.load.*
import core.runtime.EventOrder
import core.runtime.accounting.*
import core.runtime.conservative.*
import core.types.Tick
import core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, REPORT_MANAGER_ACTOR_NAME}
import org.apache.pekko.actor.{ActorRef, Props, Terminated}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{StartSimulationTimeEvent, StopSimulationEvent}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Global agreement is once per causal batch; model traffic remains between LTMs. */
class ChannelClosedGlobalTimeManager(duration: Tick, extendAfterEnd: Boolean, manager: ActorRef)
  extends GlobalTimeManagerBase(duration, manager, GLOBAL_TIME_MANAGER_ACTOR_NAME) {
  private val epoch = java.util.UUID.randomUUID().toString
  private val workers = mutable.Set.empty[ActorRef]
  private val known = mutable.Set.empty[LogicalProcessId]
  private val birthReservations = mutable.Map.empty[LogicalProcessId, RuntimeBirthOrigin]
  private val waitingJoins = mutable.ArrayBuffer.empty[JoinRuntime]
  private val ready = mutable.Map.empty[ActorRef, ConservativeReady]
  private var pending = Set.empty[ActorRef]
  private var usedSinks = Set.empty[ActorRef]
  private var pendingStart: Option[StartSimulationTimeEvent] = None
  private var started = false
  private var round = 0L
  private var lastStage: Option[(Long, Long)] = None
  private var horizon = Long.MaxValue - 1L
  private var truncated = false
  private enum Phase { case Loading, Collecting, Executing, WaitingInput, Paused, Stopping, Draining, Failed, Finished }
  import Phase.*
  private var phase = Loading
  private var producer: Option[ActorRef] = None
  private var loadedThrough = Long.MaxValue
  private var inputClosed = true
  private var lookAhead = 1000L
  private var loadingRequested = false

  protected def expectedWorkers: Int = SimulatorSettingsRegistry.getInt("htc.time-manager.total-instances", config)
  protected lazy val reportManagerRef: ActorRef = createSingletonProxy(REPORT_MANAGER_ACTOR_NAME)
  override protected def runtimeCoordinatorForActors: Option[ActorRef] = Some(self)
  override protected def localTimeManagerProps(): Props = ChannelAwareLocalTimeManager.props(duration, manager, self)
  override def onStart(): Unit = createTimeManagersPool()

  private def membership: Vector[ActorRef] = workers.toVector.sortBy(_.path.toString)
  private def join(event: JoinRuntime): Unit = {
    val id = LogicalProcessId(event.identity.classType, event.identity.id)
    require(!known.contains(id), s"duplicate conservative LP $id")
    val birth = event.birth.exists(origin => birthReservations.get(id).contains(origin))
    require(!started || phase == WaitingInput || (birth && phase == Executing), "unannounced actor creation during execution")
    require(!started || lastStage.forall(s => if (birth) event.startTick >= s._1 else event.startTick > s._1),
      "new LP below conservative frontier")
    if (birth) require(event.initialWake.isEmpty && event.startTick == event.birth.get.tick, "invalid inert child enrollment")
    known.add(id)
    val owner = membership(Math.floorMod(id.hashCode(), membership.size))
    owner ! RegisterConservativeProcess(epoch, self, event)
  }
  private def fail(error: Throwable): Unit = if (phase != Failed && phase != Finished) {
    phase = Failed
    logError(s"[CONSERVATIVE-FAILED] ${error.getMessage}")
    workers.foreach(_ ! RuntimeFailure(error.getMessage))
    manager ! RuntimeFailure(error.getMessage)
  }
  private def protect(body: => Unit): Unit = try body catch { case NonFatal(e) => fail(e) }

  override def handleEvent: Receive = {
    case _ if phase == Failed || phase == Finished => ()
    case JoinRuntimeManager(ref) => protect {
      require(sender() == ref && !started, "time-manager membership changed during execution")
      workers.add(ref)
      context.watch(ref)
      require(workers.size <= expectedWorkers, "unexpected conservative worker")
      if (workers.size == expectedWorkers) {
        val update = ConservativeMembership(epoch, membership)
        workers.foreach(_ ! update)
        waitingJoins.foreach(join)
        waitingJoins.clear()
        maybeStart()
      }
    }
    case event: JoinRuntime => protect {
      require(sender() == event.ref, "LP registration sender mismatch")
      if (workers.size < expectedWorkers) waitingJoins += event else join(event)
    }
    case RequestRuntimeBirth(run, target, origin) => protect {
      require(run == epoch && phase == Executing && known.contains(origin.parent), "birth outside conservative execution")
      require(membership(Math.floorMod(target.hashCode(), membership.size)) == sender() &&
        lastStage.forall(origin.tick >= _._1), "invalid conservative birth authority/frontier")
      require(!known.contains(target) && !birthReservations.contains(target), "duplicate birth reservation")
      birthReservations(target) = origin
      sender() ! AuthorizeRuntimeBirth(epoch, target, origin)
    }
    case event: RegisterProgressiveLoadManagerEvent => protect {
      require(!started && producer.isEmpty, "input producer registered after startup")
      producer = Some(event.progressiveLoadManager)
      inputClosed = false
      loadedThrough = Long.MinValue
      lookAhead = event.lookAheadTicks
      event.ackTo.foreach(_ ! ProgressiveLoadManagerRegisteredEvent(lookAhead))
    }
    case _: TickWindowReady if inputClosed && producer.nonEmpty => ()
    case event: TickWindowReady => protect {
      require(event.readyUpToTick >= loadedThrough, "input frontier regression")
      loadedThrough = event.readyUpToTick
      if (loadedThrough == Long.MaxValue) inputClosed = true
      loadingRequested = false
      if (!started) maybeStart()
      else if (phase == WaitingInput) collect()
    }
    case _: ProgressiveLoadingCompleteEvent =>
      self ! TickWindowReady(Long.MaxValue, 0L)
    case event: StartSimulationTimeEvent => startSimulation(event)
    case event: ConservativeReady => protect {
      require(workers.contains(sender()), "frontier from unregistered worker")
      if (phase == Collecting && event.round == round) {
        ready(sender()) = event
        if (ready.size == workers.size) chooseBatch()
      }
    }
    case ConservativeBatchComplete(id) => protect {
      require(workers.contains(sender()), "completion from unregistered worker")
      if (phase == Executing && id == round) {
        pending -= sender()
        if (pending.isEmpty) {
          if (isPaused) phase = Paused else collect()
        }
      }
    }
    case ConservativeRuntimeStopped(id, sinks) => protect {
      require(workers.contains(sender()), "retirement from unregistered worker")
      if (phase == Stopping && id == round) {
        pending -= sender()
        usedSinks ++= sinks
        if (pending.isEmpty) {
          phase = Draining
          reportManagerRef ! DrainRuntimeReports(round, self, usedSinks)
        }
      }
    }
    case RuntimeReportsFlushed(id, error) if phase == Draining && id == round => protect {
      error.foreach(e => throw new IllegalStateException(e))
      phase = Finished
      logInfo(s"[CONSERVATIVE-COMPLETE] tick=$localTickOffset status=${if (truncated) "horizon-truncated" else "completed"}")
      manager ! StopSimulationEvent()
    }
    case Terminated(ref) if workers.contains(ref) => fail(new IllegalStateException(s"conservative worker lost: $ref"))
    case RuntimeFailure(reason) => fail(new IllegalStateException(reason))
    case _: TimeManagerRegisterEvent => ()
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = protect {
    require(!started && pendingStart.isEmpty, "conservative runtime already started")
    pendingStart = Some(event)
    initialTick = event.startTick
    horizon = if (extendAfterEnd) Long.MaxValue - 1L else Math.addExact(initialTick, duration)
    if (!inputClosed && !loadingRequested) requestInput(initialTick)
    maybeStart()
  }
  private def requestInput(from: Long): Unit = {
    require(producer.nonEmpty && !loadingRequested, "overlapping input request")
    phase = WaitingInput
    loadingRequested = true
    producer.get ! TickWindowRequest(from, Math.addExact(from, lookAhead))
  }
  private def maybeStart(): Unit = if (!started && pendingStart.nonEmpty && workers.size == expectedWorkers &&
    (inputClosed || loadedThrough >= initialTick)) {
    started = true
    startTime = System.currentTimeMillis()
    pendingStart = None
    collect()
  }
  private def collect(): Unit = {
    phase = Collecting
    round = Math.addExact(round, 1L)
    ready.clear()
    workers.foreach(_ ! CollectConservativeReady(round))
  }
  private def chooseBatch(): Unit = {
    if (isPaused) { phase = Paused; return }
    val next = ready.valuesIterator.flatMap(_.next).minOption
    if (!inputClosed && (next.isEmpty || next.exists(_.tick > loadedThrough))) {
      requestInput(Math.addExact(loadedThrough, 1L))
    } else if (next.isEmpty || next.exists(_.tick > horizon)) {
      truncated = next.nonEmpty
      require(truncated || ready.valuesIterator.forall(_.blocked == 0), "unresolved replies without future work")
      phase = Stopping
      pending = workers.toSet
      workers.foreach(_ ! StopConservativeRuntime(round, truncated))
    } else {
      val order = next.get
      val stage = order.tick -> order.microstep
      require(lastStage.forall(s => Ordering.Tuple2[Long, Long].lt(s, stage)), s"conservative frontier regression: $lastStage -> $stage")
      lastStage = Some(stage)
      localTickOffset = order.tick
      phase = Executing
      pending = workers.toSet
      workers.foreach(_ ! ExecuteConservativeBatch(round, order.tick, order.microstep))
    }
  }

  override protected def pauseSimulation(): Unit = { isPaused = true; if (phase == Collecting) phase = Paused }
  override protected def resumeSimulation(): Unit = { isPaused = false; if (phase == Paused) collect() }
  override protected def stopSimulation(): Unit = fail(new IllegalStateException("simulation cancelled externally"))
  protected def registerActor(event: org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent): Unit = ()
  protected def scheduleEvent(event: ScheduleEvent): Unit = throw new IllegalStateException("unowned conservative schedule")
  protected def finishEvent(event: FinishEvent): Unit = ()
  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = ()
  protected def advanceToNextTick(): Unit = ()
  protected def nextTick: Option[Tick] = None
}
