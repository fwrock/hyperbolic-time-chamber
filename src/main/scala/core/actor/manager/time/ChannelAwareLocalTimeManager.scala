package org.interscity.htc
package core.actor.manager.time

import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.runtime.EventOrder
import core.runtime.accounting.*
import core.runtime.conservative.*
import core.types.Tick
import core.util.ManagerConstantsUtil.LOCAL_TIME_MANAGER_ACTOR_NAME
import org.apache.pekko.actor.{ActorRef, Props, Terminated}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent
import scala.collection.mutable
import scala.util.control.NonFatal

/** A worker owns channel ledgers and LP queues. Only frontier summaries cross the GTM. */
class ChannelAwareLocalTimeManager(duration: Tick, manager: ActorRef, coordinator: ActorRef)
  extends LocalTimeManagerBase(duration, manager, Some(coordinator), LOCAL_TIME_MANAGER_ACTOR_NAME) {
  accountedRuntime = Some(coordinator)
  private case class Process(id: LogicalProcessId, ref: ActorRef, identity: Identify, handle: Long,
    incarnation: String, var next: Option[EventOrder] = None, var blocked: Int = 0, var terminal: Boolean = false)
  private case class Acceptance(process: Process, source: Option[(ActorRef, Long)])
  private val processes = mutable.Map.empty[LogicalProcessId, Process]
  private val byRef = mutable.Map.empty[ActorRef, Process]
  private val byHandle = mutable.LongMap.empty[Process]
  private val readyInputs = mutable.Map.empty[ActorRef, EventOrder]
  private val blockedProcesses = mutable.Set.empty[ActorRef]
  private val terminalProcesses = mutable.Set.empty[ActorRef]
  private val outgoing = mutable.LongMap.empty[ActorRef]
  private val accepting = mutable.LongMap.empty[Acceptance]
  private val reports = mutable.Set.empty[Long]
  private val sinks = mutable.Set.empty[ActorRef]
  private var members = Vector.empty[ActorRef]
  private var epoch = ""
  private var token = 0L
  private var handle = 0L
  private var batch: Option[ExecuteConservativeBatch] = None
  private var preparing = false
  private var dispatching = false
  private var executing = Set.empty[ActorRef]
  private var stopping: Option[StopConservativeRuntime] = None
  private var retirements = Set.empty[ActorRef]
  private val retiring = mutable.Map.empty[ActorRef, Long]
  private var failed = false
  private var readyRequest: Option[(Long, ActorRef)] = None

  private def nextToken(): Long = { token = Math.addExact(token, 1L); token }
  private def owner(id: LogicalProcessId): ActorRef = members(Math.floorMod(id.hashCode(), members.size))
  private def checkEpoch(value: String): Unit = require(value == epoch && epoch.nonEmpty, "wrong conservative epoch")
  private def updateInput(process: Process, next: Option[EventOrder]): Unit = {
    process.next = next
    next match {
      case Some(order) => readyInputs(process.ref) = order
      case None => readyInputs.remove(process.ref)
    }
  }
  private def input(process: Process, event: AnyRef, source: Option[(ActorRef, Long)]): Unit = {
    val id = nextToken()
    accepting(id) = Acceptance(process, source)
    process.ref ! ConservativeDelivery(epoch, id, event)
  }
  protected def transferTo(destination: ActorRef, event: ConservativeTransfer): Unit = destination ! event
  private def fail(error: Throwable): Unit = if (!failed) {
    failed = true
    coordinator ! RuntimeFailure(s"conservative worker ${self.path}: ${error.getMessage}")
  }

  override def handleEvent: Receive = {
    val conservative: Receive = {
    case _ if failed => ()
    case event: ConservativeMembership => protect {
      require(sender() == coordinator && epoch.isEmpty, "membership changed after registration")
      require(event.managers.contains(self), "worker omitted from membership")
      epoch = event.epoch
      members = event.managers
    }
    case RegisterConservativeProcess(run, authority, join) => protect {
      checkEpoch(run)
      require(sender() == coordinator && authority == coordinator, "unowned LP registration")
      val id = LogicalProcessId(join.identity.classType, join.identity.id)
      require(owner(id) == self && !processes.contains(id), "duplicate or wrong-owner registration")
      handle = Math.addExact(handle, 1L)
      val process = Process(id, join.ref, join.identity, handle, join.incarnation)
      processes(id) = process
      byRef(join.ref) = process
      byHandle(handle) = process
      context.watch(join.ref)
      registerOwnedProcess(join.identity.copy(id = handle), join.initialWake, join.identity)
      join.ref ! RuntimeJoined(epoch, coordinator, self)
    }
    case RouteInteraction(run, target, event) => protect {
      checkEpoch(run)
      val source = byRef.getOrElse(sender(), throw new IllegalArgumentException("unregistered model sender"))
      require(!hasStarted || executing.contains(sender()), "model send outside an authorized batch")
      require(target.model.nonEmpty, "conservative transport requires a typed LP destination")
      require(!event.isAntiMessage && event.order.nonEmpty, "invalid conservative interaction")
      require(event.actorRefId == source.id.id && event.actorClassType == source.id.model &&
        event.sourceIncarnation == source.incarnation && event.sourceEpoch == epoch, "source incarnation mismatch")
      batch.foreach(b => require(event.order.exists(o => Ordering.Tuple2[Long, Long].gt((o.tick, o.microstep), (b.tick, b.microstep))),
        "model output did not advance causal time"))
      val id = nextToken()
      val destination = owner(target)
      outgoing(id) = destination
      transferTo(destination, ConservativeTransfer(epoch, id, target, event))
    }
    case event: ConservativeTransfer => protect {
      checkEpoch(event.epoch)
      require(members.contains(sender()) && owner(event.target) == self, "unowned channel delivery")
      val process = processes.getOrElse(event.target,
        throw new IllegalArgumentException(s"interaction to unregistered LP ${event.target}"))
      require(!retiring.contains(process.ref), s"interaction to terminal LP ${event.target}")
      input(process, event.event, Some(sender() -> event.token))
    }
    case ConservativeInputAccepted(run, id, next) => protect {
      checkEpoch(run)
      val accepted = accepting.remove(id).getOrElse(throw new IllegalArgumentException("unknown input acceptance"))
      require(sender() == accepted.process.ref, "input acceptance from wrong LP")
      updateInput(accepted.process, next)
      accepted.source.foreach { case (source, token) => source ! ConservativeTransferAccepted(epoch, token) }
      executePreparedBatch()
      completeBatch()
    }
    case ConservativeTransferAccepted(run, id) => protect {
      checkEpoch(run)
      require(outgoing.remove(id).contains(sender()), "wrong channel acknowledgement")
      completeBatch()
    }
    case RouteControl(run, target, control, _) => protect {
      checkEpoch(run)
      require(target == self, "schedule control sent to wrong owner")
      require(!hasStarted || executing.contains(sender()), "schedule update outside an authorized batch")
      val source = byRef.getOrElse(sender(), throw new IllegalArgumentException("unregistered schedule source"))
      def identity(value: Identify): Identify = {
        require(value.id == source.id.id, "schedule identity mismatch")
        value.copy(id = source.handle, classType = source.id.model)
      }
      val local = control match {
        case e: FinishEvent => e.copy(identify = identity(e.identify))
        case e: ScheduleEvent => e.copy(identify = Some(identity(e.identify.get)))
        case _ => throw new IllegalArgumentException("unsupported conservative schedule control")
      }
      local match {
        case e: FinishEvent => finishEvent(e)
        case e: ScheduleEvent => scheduleEvent(e)
      }
    }
    case CollectConservativeReady(round) => protect {
      require(sender() == coordinator && batch.isEmpty, "frontier requested during execution")
      readyRequest = Some(round -> sender())
      reportReady()
    }
    case event: ExecuteConservativeBatch => protect {
      require(sender() == coordinator && batch.isEmpty && outgoing.isEmpty, "overlapping conservative batches")
      batch = Some(event)
      preparing = true
      retireCommittedTerminals(event.round)
      localTickOffset = event.tick
      hasStarted = true
      dispatching = true
      dispatchConservativeSchedules(event.tick, event.microstep)
      dispatching = false
      executePreparedBatch()
    }
    case ConservativeBatchProcessed(round, next, blocked, terminal) => protect {
      require(batch.exists(_.round == round) && executing.contains(sender()), "unexpected LP batch completion")
      val process = byRef(sender())
      updateInput(process, next)
      process.blocked = blocked
      process.terminal = terminal
      if (blocked > 0) blockedProcesses.add(sender()) else blockedProcesses.remove(sender())
      if (terminal) terminalProcesses.add(sender()) else terminalProcesses.remove(sender())
      executing -= sender()
      completeBatch()
    }
    case PublishConservativeReport(target, report) => protect {
      require(byRef.contains(sender()), "report from unregistered LP")
      require(!hasStarted || executing.contains(sender()), "model report outside an authorized batch")
      val id = nextToken()
      reports.add(id)
      target ! CommittedReportDelivery(epoch, id, report, self)
    }
    case DeliveryAccepted(run, id) => protect {
      checkEpoch(run)
      require(reports.remove(id), "unknown report receipt")
      sinks.add(sender())
      completeBatch()
    }
    case event: StopConservativeRuntime => protect {
      require(sender() == coordinator && batch.isEmpty && outgoing.isEmpty && accepting.isEmpty && reports.isEmpty,
        "stop before worker channels drained")
      stopping = Some(event)
      retirements = byRef.keySet.toSet
      retirements.foreach(_ ! RetireConservativeProcess(event.round, event.truncated))
      completeStop()
    }
    case ConservativeProcessRetired(round) => protect {
      if (retiring.get(sender()).contains(round)) {
        retiring.remove(sender())
        val process = byRef.remove(sender()).get
        processes.remove(process.id)
        byHandle.remove(process.handle)
        readyInputs.remove(sender())
        blockedProcesses.remove(sender())
        terminalProcesses.remove(sender())
        forgetOwnedProcess(process.handle)
        context.unwatch(sender())
        completeBatch()
      } else {
        require(stopping.exists(_.round == round) && retirements.contains(sender()), "unexpected retirement")
        retirements -= sender()
        completeStop()
      }
    }
    case Terminated(ref) if byRef.contains(ref) && stopping.isEmpty && !retiring.contains(ref) =>
      fail(new IllegalStateException(s"LP lost: $ref"))
    case failure: RuntimeFailure => fail(new IllegalStateException(failure.reason))
    }
    conservative.orElse(super.handleEvent)
  }

  private def protect(body: => Unit): Unit = try body catch { case NonFatal(e) => fail(e) }
  private def executePreparedBatch(): Unit = if (preparing && !dispatching && !accepting.valuesIterator.exists(_.source.isEmpty)) {
    val stage = batch.get
    preparing = false
    executing = readyInputs.iterator.collect {
      case (ref, order) if order.tick == stage.tick && order.microstep == stage.microstep => ref
    }.toSet
    executing.foreach(_ ! stage)
    completeBatch()
  }
  private def completeBatch(): Unit = if (batch.nonEmpty && !preparing && executing.isEmpty &&
    outgoing.isEmpty && accepting.isEmpty && reports.isEmpty && retiring.isEmpty) {
    val round = batch.get.round
    batch = None
    coordinator ! ConservativeBatchComplete(round)
  } else reportReady()
  private def reportReady(): Unit = if (readyRequest.nonEmpty && batch.isEmpty && outgoing.isEmpty &&
    accepting.isEmpty && reports.isEmpty) {
    val (round, target) = readyRequest.get
    readyRequest = None
    val next = (readyOrderedSchedules.map(_._3) ++ readyInputs.valuesIterator).minOption
    target ! ConservativeReady(round, next, blockedProcesses.size + runningEvents.size)
  }
  private def completeStop(): Unit = if (stopping.nonEmpty && retirements.isEmpty) {
    coordinator ! ConservativeRuntimeStopped(stopping.get.round, sinks.toSet)
  }

  private def retireCommittedTerminals(round: Long): Unit = {
    val running = runningEvents.iterator.map(_.id).toSet
    val terminal = terminalProcesses.iterator.map(byRef).filter(p => p.terminal && p.next.isEmpty && p.blocked == 0 &&
      !running.contains(p.handle)).toVector
    // A new GTM grant proves every worker drained the previous batch, including messages
    // sent by other LTMs. Local completion alone cannot authorize physical retirement.
    terminal.foreach(p => retiring(p.ref) = round)
    terminal.foreach(_.ref ! RetireConservativeProcess(round, false))
  }

  override protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit =
    input(byHandle(identity.id), prepareOwnedDispatch(tick, identity), None)
  override protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    startTime = System.currentTimeMillis()
    initialTick = event.startTick
    hasStarted = true
  }
  override protected def processTick(tick: Tick): Unit = ()
  override protected def advanceToNextTick(): Unit = ()
  override protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit = ()
}

object ChannelAwareLocalTimeManager {
  def props(duration: Tick, manager: ActorRef, coordinator: ActorRef): Props =
    Props(new ChannelAwareLocalTimeManager(duration, manager, coordinator))
}
