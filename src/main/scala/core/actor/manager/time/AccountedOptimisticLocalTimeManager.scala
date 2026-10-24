package org.interscity.htc
package core.actor.manager.time

import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.entity.event.control.execution.ReplaceScheduleEvent
import core.runtime.accounting.*
import core.runtime.distributed.*
import core.actor.manager.load.RuntimeBirthInitializer
import core.types.Tick
import core.runtime.OptimisticExecutionLimits
import core.util.StringUtil
import org.apache.pekko.actor.{ActorRef, Props, Terminated}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent
import scala.collection.mutable
import scala.util.control.NonFatal

/** A frozen summary is sent only after local callbacks and all outgoing channels drain. */
class AccountedOptimisticLocalTimeManager(duration: Tick, manager: ActorRef, global: ActorRef)
  extends OptimisticLocalTimeManager(duration, manager, Some(global), Some(global)) {
  private case class Process(id: LogicalProcessId, ref: ActorRef, handle: Long, incarnation: String)
  private case class Acceptance(process: Process, source: Option[(ActorRef, Long)])
  private enum Phase { case Loading, Enrolling, Running, Freezing, Frozen, Collecting, Committing, Committed, Retiring, Paused, Stopped, Failed }
  import Phase.*
  private var phase = Loading
  private var epoch = ""
  private var managers = Vector.empty[ActorRef]
  private var token = 0L
  private var handle = 0L
  private var round = 0L
  private var horizon = Long.MaxValue - 1L
  private var gvt = Long.MinValue
  private var beyondHorizon: Option[Long] = None
  private val processes = mutable.Map.empty[LogicalProcessId, Process]
  private val byRef = mutable.Map.empty[ActorRef, Process]
  private val byHandle = mutable.LongMap.empty[Process]
  private val outgoing = mutable.LongMap.empty[ActorRef]
  private val accepting = mutable.LongMap.empty[Acceptance]
  private val reports = mutable.Set.empty[Long]
  private val sinks = mutable.Set.empty[ActorRef]
  private var pending = Set.empty[ActorRef]
  private var eligible = Set.empty[ActorRef]
  private var snapshots = Map.empty[ActorRef, RuntimeSnapshot]
  private var transfers = 0L
  private var receipts = 0L
  private var retiringFinal = false
  private var limits = OptimisticExecutionLimits()
  private var executionFrontier = 0L
  private var inputThrough = Long.MaxValue
  private val unborn = mutable.Map.empty[LogicalProcessId, mutable.ArrayBuffer[(core.entity.event.ActorInteractionEvent, Option[(ActorRef, Long)])]]
  private val births = mutable.Map.empty[LogicalProcessId, (RuntimeBirthOrigin, RuntimeBirthData, Option[ActorRef])]
  runtimeFrozen = true

  override protected def currentGvt: Option[Tick] = if (gvt == Long.MinValue) None else Some(gvt)
  override protected def dispatchCeiling: Tick = math.min(inputThrough, limits.ceiling(executionFrontier, horizon))
  override protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit = ()
  protected def transferTo(destination: ActorRef, event: OptimisticTransfer): Unit = destination ! event
  private def nextToken(): Long = { token = Math.addExact(token, 1L); token }
  private def owner(id: LogicalProcessId): ActorRef = managers(Math.floorMod(id.hashCode(), managers.size))
  private def checkEpoch(run: String): Unit = require(epoch.nonEmpty && run == epoch, "wrong optimistic epoch")
  private def channelsDrained: Boolean = outgoing.isEmpty && accepting.isEmpty && births.isEmpty
  protected def createRuntimeBirthInitializer(target: LogicalProcessId, origin: RuntimeBirthOrigin, data: RuntimeBirthData): ActorRef =
    context.actorOf(RuntimeBirthInitializer.props(self, global, target, origin, data))
  private def source(): Process = byRef.getOrElse(sender(), throw new IllegalArgumentException("unregistered local LP"))
  private def fail(error: Throwable): Unit = if (phase != Failed && phase != Stopped) {
    phase = Failed
    runtimeFrozen = true
    byRef.keys.foreach(_ ! FreezeRuntime(round))
    global ! RuntimeFailure(s"optimistic worker ${self.path}: ${error.getMessage}")
  }
  private def protect(body: => Unit): Unit = try body catch { case NonFatal(e) => fail(e) }
  private def input(process: Process, event: AnyRef, source: Option[(ActorRef, Long)]): Unit = {
    val id = nextToken()
    accepting(id) = Acceptance(process, source)
    process.ref ! RuntimeDelivery(epoch, id, event)
  }
  private def allowModelRoute(): Unit = {
    require(phase == Loading || phase == Running || phase == Freezing,
      s"model route outside execution/draining: $phase")
    require(!(phase == Freezing && !pending.contains(sender())),
      "model route after local freeze acknowledgement")
  }

  override def handleEvent: Receive = {
    val distributed: Receive = {
      case _ if phase == Failed || phase == Stopped => ()
      case RuntimeMembership(run, members) => protect {
        require(sender() == global && epoch.isEmpty && members.contains(self), "invalid membership")
        epoch = run
        managers = members
      }
      case RegisterLocalProcess(run, join) => protect {
        checkEpoch(run)
        require(sender() == global && (phase == Loading || phase == Enrolling ||
          (join.birth.nonEmpty && (phase == Running || phase == Freezing || phase == Paused))), "registration outside input enrollment")
        require(join.startTick >= gvt && join.initialWake.forall(_.tick >= gvt), "new LP below GVT")
        val id = LogicalProcessId(join.identity.classType, join.identity.id)
        require(owner(id) == self && !processes.contains(id) && !byRef.contains(join.ref), "duplicate or wrong-owner LP")
        handle = Math.addExact(handle, 1L)
        val process = Process(id, join.ref, handle, join.incarnation)
        processes(id) = process
        byRef(join.ref) = process
        byHandle(handle) = process
        context.watch(join.ref)
        registerOwnedProcess(join.identity.copy(id = handle), join.initialWake, join.identity)
        global ! LocalProcessRegistered(id)
        join.ref ! RuntimeJoined(epoch, global, self, distributed = true)
        if (phase != Loading) {
          join.ref ! SetRuntimeHorizon(horizon)
          if (join.birth.nonEmpty) {
            join.ref ! SetOptimisticExecutionLimits(executionFrontier, dispatchCeiling, limits)
            if (phase == Running) join.ref ! ResumeRuntime(round)
            else if (phase == Freezing) { pending += join.ref; join.ref ! FreezeRuntime(round) }
          }
        }
      }
      case AuthorizeRuntimeBirth(run, target, origin) => protect {
        checkEpoch(run)
        require(sender() == global && births.get(target).exists(b => b._1 == origin && b._3.isEmpty), "invalid birth grant")
        val data = births(target)._2
        val initializer = createRuntimeBirthInitializer(target, origin, data)
        context.watch(initializer)
        births(target) = (origin, data, Some(initializer))
      }
      case RuntimeBirthInitialized(target, ref) => protect {
        require(births.get(target).exists(_._3.contains(sender())) && processes.get(target).exists(_.ref == ref),
          "birth initialized without owned registration")
        births.remove(target)
        context.unwatch(sender())
        sender() ! StopRuntimeBirthInitializer
        unborn.remove(target).get.foreach { case (event, source) => input(processes(target), event, source) }
        maybeFrozen()
      }
      case StartLocalRuntime(start, end, executionLimits, through) => protect {
        require(sender() == global && phase == Loading, "duplicate runtime start")
        horizon = end
        limits = executionLimits
        executionFrontier = start
        inputThrough = through
        require(inputThrough >= start, "initial input window excludes the start tick")
        phase = Running
        runtimeFrozen = false
        byRef.keys.foreach { ref =>
          ref ! SetRuntimeHorizon(horizon)
          ref ! SetOptimisticExecutionLimits(executionFrontier, dispatchCeiling, limits)
          ref ! ResumeRuntime(0L)
        }
        startSimulation(StartSimulationTimeEvent(startTick = start))
      }
      case BeginInputEnrollment(id) => protect {
        require(sender() == global && phase == Paused && id == round && channelsDrained,
          "input enrollment outside a paused, drained round")
        phase = Enrolling
        global ! InputEnrollmentOpened(round)
      }
      case CompleteInputEnrollment(id, through) => protect {
        require(sender() == global && phase == Enrolling && id == round && through > inputThrough,
          "invalid input enrollment completion")
        inputThrough = through
        byRef.keys.foreach(_ ! SetOptimisticExecutionLimits(executionFrontier, dispatchCeiling, limits))
        phase = Paused
        global ! InputEnrollmentCompleted(round)
      }
      case RouteInteraction(run, target, event) => protect {
        checkEpoch(run)
        val process = source()
        allowModelRoute()
        require(target.model.nonEmpty, s"optimistic transport requires a typed destination: $target")
        require(event.isAntiMessage || event.order.nonEmpty, "positive interaction lacks logical event order")
        require(event.actorRefId == process.id.id && event.actorClassType == process.id.model &&
          event.sourceEpoch == epoch && event.sourceIncarnation == process.incarnation, "source incarnation mismatch")
        require(event.tick >= gvt, s"model send ${event.tick} below GVT $gvt")
        if (event.tick > horizon) beyondHorizon = Some(beyondHorizon.fold(event.tick)(math.min(_, event.tick)))
        else {
          val id = nextToken()
          val destination = owner(target)
          outgoing(id) = destination
          transfers += 1L
          transferTo(destination, OptimisticTransfer(epoch, id, target, event))
        }
      }
      case event: OptimisticTransfer => protect {
        checkEpoch(event.epoch)
        require(managers.contains(sender()) && owner(event.target) == self, "unowned transfer")
        require(phase == Loading || phase == Running || phase == Freezing || phase == Frozen || phase == Paused,
          s"transfer after channel closure: $phase")
        require(event.event.tick >= gvt && event.event.tick <= horizon, "transfer outside execution frontier")
        processes.get(event.target) match {
          case Some(process) => input(process, event.event, Some(sender() -> event.token))
          case None =>
            val queue = unborn.getOrElseUpdate(event.target, mutable.ArrayBuffer.empty)
            event.event.data match {
              case data: RuntimeBirthData if !event.event.isAntiMessage =>
                val origin = RuntimeBirthOrigin(LogicalProcessId(event.event.actorClassType, event.event.actorRefId),
                  event.event.order.get.key, event.event.tick, event.event.order.get.microstep)
                if (!births.contains(event.target)) {
                  births(event.target) = (origin, data, None)
                  if (phase == Frozen) phase = Freezing
                  global ! RequestRuntimeBirth(epoch, event.target, origin)
                } else require(births(event.target)._1 == origin, "conflicting child birth slot")
                queue += event.event -> Some(sender() -> event.token)
              case _ =>
                // The owner holds these inputs until the birth positive arrives. Its sender
                // remains accounted separately until that positive reaches the initialized LP.
                queue += event.event -> None
                sender() ! OptimisticTransferAccepted(epoch, event.token)
            }
        }
      }
      case OptimisticTransferAccepted(run, id) => protect {
        checkEpoch(run)
        require(outgoing.remove(id).contains(sender()), "unknown or wrong-source transfer receipt")
        receipts += 1L
        maybeFrozen()
      }
      case DeliveryAccepted(run, id) => protect {
        checkEpoch(run)
        if (reports.remove(id)) {
          sinks.add(sender())
          maybeCommitted()
        } else {
          val accepted = accepting.remove(id).getOrElse(throw new IllegalArgumentException("unknown input receipt"))
          require(sender() == accepted.process.ref, "input receipt from wrong LP")
          accepted.source.foreach { case (ref, original) => ref ! OptimisticTransferAccepted(epoch, original) }
          maybeFrozen()
        }
      }
      case RouteControl(run, target, event, tick) => protect {
        checkEpoch(run)
        val process = source()
        // An obsolete dispatch may release its slot while its frozen LP accepts delivery.
        val obsoleteDispatch = event match {
          case finish: FinishEvent => finish.discardedDispatch && accepting.valuesIterator.exists(_.process.ref == sender())
          case _ => false
        }
        require(phase == Loading || phase == Running || (phase == Freezing && pending.contains(sender())) ||
          ((phase == Freezing || phase == Frozen || phase == Paused) && obsoleteDispatch), "schedule update after local freeze acknowledgement")
        require(target == self && tick >= gvt, "schedule update to wrong owner/below GVT")
        def identity(value: Identify): Identify = {
          require(value.id == process.id.id && (value.classType == process.id.model ||
            StringUtil.getModelClassName(value.classType) == process.id.model), "schedule identity mismatch")
          value.copy(id = process.handle, classType = process.id.model)
        }
        event match {
          case e: FinishEvent => finishEvent(e.copy(identify = identity(e.identify)))
          case e: ReplaceScheduleEvent => replaceSchedule(e.copy(identify = identity(e.identify)))
          case e: ScheduleEvent => scheduleEvent(e.copy(identify = Some(identity(e.identify.get))))
          case _ => throw new IllegalArgumentException("unsupported schedule control")
        }
      }
      case FreezeRuntime(id) => protect {
        require(sender() == global && phase == Running && id > round, "invalid freeze round")
        round = id
        phase = Freezing
        runtimeFrozen = true
        pending = byRef.keySet.toSet
        pending.foreach(_ ! FreezeRuntime(round))
        maybeFrozen()
      }
      case RuntimeFrozen(id) => protect {
        require(phase == Freezing && id == round && pending.contains(sender()), "unexpected LP freeze")
        pending -= sender()
        maybeFrozen()
      }
      case CollectRuntimeSnapshot(id) => protect {
        require(sender() == global && phase == Frozen && id == round && channelsDrained, "snapshot before channel closure")
        require(unborn.isEmpty, "input to an unknown/retired LP without an accounted birth")
        phase = Collecting
        snapshots = Map.empty
        pending = byRef.keySet.toSet
        pending.foreach(_ ! CollectRuntimeSnapshot(round))
        maybeSnapshot()
      }
      case snapshot: RuntimeSnapshot => protect {
        require(phase == Collecting && snapshot.round == round && pending.contains(sender()), "unexpected LP snapshot")
        snapshots += sender() -> snapshot
        pending -= sender()
        maybeSnapshot()
      }
      case CommitRuntime(id, frontier) => protect {
        require(sender() == global && phase == Collecting && pending.isEmpty && id == round && frontier >= gvt,
          "commit outside collected round")
        gvt = frontier
        executionFrontier = frontier
        phase = Committing
        eligible = Set.empty
        pending = byRef.keySet.toSet
        pending.foreach { ref =>
          ref ! SetOptimisticExecutionLimits(executionFrontier, dispatchCeiling, limits)
          ref ! CommitRuntime(round, gvt)
        }
        maybeCommitted()
      }
      case RuntimeCommitted(id, mayRetire) => protect {
        require(phase == Committing && id == round && pending.contains(sender()), "unexpected LP commit")
        pending -= sender()
        if (mayRetire) eligible += sender()
        maybeCommitted()
      }
      case PublishCommittedReport(target, event) => protect {
        require(phase == Committing && pending.contains(sender()) && event.tick < gvt, "report outside commit transaction")
        val id = nextToken()
        reports.add(id)
        target ! CommittedReportDelivery(epoch, id, event, self)
      }
      case RetireLocalProcesses(id, finalCut) => protect {
        require(sender() == global && id == round && phase == Committed, "retirement outside committed round")
        phase = Retiring
        retiringFinal = finalCut
        pending = if (finalCut) byRef.keySet.toSet else eligible
        pending.foreach(_ ! RetireRuntimeProcess(round, finalCut))
        maybeRetired()
      }
      case RuntimeProcessRetired(id) => protect {
        require(phase == Retiring && id == round && pending.contains(sender()), "unexpected LP retirement")
        pending -= sender()
        val process = byRef.remove(sender()).get
        processes.remove(process.id)
        byHandle.remove(process.handle)
        forgetOwnedProcess(process.handle)
        context.unwatch(sender())
        maybeRetired()
      }
      case ResumeRuntime(id) => protect {
        require(sender() == global && phase == Paused && id == round, "invalid resume round")
        phase = Running
        runtimeFrozen = false
        byRef.keys.foreach(_ ! ResumeRuntime(round))
        advanceToNextTick()
      }
      case Terminated(ref) if byRef.contains(ref) && !(phase == Retiring && pending.contains(ref)) =>
        fail(new IllegalStateException(s"LP lost before safe retirement: $ref"))
      case Terminated(ref) if births.valuesIterator.exists(_._3.contains(ref)) =>
        fail(new IllegalStateException(s"birth initializer lost: $ref"))
      case RuntimeFailure(reason) => fail(new IllegalStateException(reason))
    }
    distributed.orElse(super.handleEvent)
  }

  private def maybeFrozen(): Unit = if (phase == Freezing && pending.isEmpty && channelsDrained) {
    phase = Frozen
    global ! RuntimeFrozen(round)
  }
  private def maybeSnapshot(): Unit = if (phase == Collecting && pending.isEmpty) {
    runningEvents.foreach { identity =>
      val process = byHandle(identity.id)
      val snapshot = snapshots(process.ref)
      require(snapshot.blocked > 0 || snapshot.queuedDispatches > 0,
        s"dispatch has neither queued input nor a reply obligation: ${process.id}")
    }
    // Accepted dispatches are counted by LP queues. A retained slot awaiting a model reply is
    // not unfinished computation and must not pin GVT below that reply's future causal work.
    val minimum = (nextTick.toSeq ++ snapshots.valuesIterator.flatMap(_.minimum) ++ beyondHorizon).minOption
    val retention = snapshots.valuesIterator.flatMap(_.retention).foldLeft(RuntimeRetention())(_ + _)
    global ! RuntimeSnapshot(round, minimum, snapshots.valuesIterator.map(_.blocked).sum,
      snapshots.valuesIterator.map(_.callbacks).sum, retention = Some(retention))
  }
  private def maybeCommitted(): Unit = if (phase == Committing && pending.isEmpty && reports.isEmpty) {
    phase = Committed
    global ! LocalRuntimeCommitted(round, sinks.toSet)
  }
  private def maybeRetired(): Unit = if (phase == Retiring && pending.isEmpty) {
    phase = if (retiringFinal) Stopped else Paused
    if (retiringFinal) isStopped = true
    global ! LocalProcessesRetired(round)
    logInfo(s"[LTM-ACCOUNTING] round=$round transfers=$transfers receipts=$receipts outgoing=${outgoing.size} accepting=${accepting.size}")
  }
  override protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    require(phase == Running && tick <= horizon, "dispatch outside execution")
    input(byHandle(identity.id), prepareOwnedDispatch(tick, identity), None)
  }
}

object AccountedOptimisticLocalTimeManager {
  def props(duration: Tick, manager: ActorRef, global: ActorRef): Props =
    Props(new AccountedOptimisticLocalTimeManager(duration, manager, global))
}
