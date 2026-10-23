package org.interscity.htc
package core.actor.manager.time

import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.entity.event.control.execution.ReplaceScheduleEvent
import core.runtime.accounting.*
import core.runtime.distributed.*
import core.types.Tick
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
  private enum Phase { case Loading, Running, Freezing, Frozen, Collecting, Committing, Committed, Retiring, Paused, Stopped, Failed }
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
  runtimeFrozen = true

  override protected def currentGvt: Option[Tick] = if (gvt == Long.MinValue) None else Some(gvt)
  override protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit = ()
  protected def transferTo(destination: ActorRef, event: OptimisticTransfer): Unit = destination ! event
  private def nextToken(): Long = { token = Math.addExact(token, 1L); token }
  private def owner(id: LogicalProcessId): ActorRef = managers(Math.floorMod(id.hashCode(), managers.size))
  private def checkEpoch(run: String): Unit = require(epoch.nonEmpty && run == epoch, "wrong optimistic epoch")
  private def channelsDrained: Boolean = outgoing.isEmpty && accepting.isEmpty
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
        require(sender() == global && phase == Loading, "registration outside loading")
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
      }
      case StartLocalRuntime(start, end) => protect {
        require(sender() == global && phase == Loading, "duplicate runtime start")
        horizon = end
        phase = Running
        runtimeFrozen = false
        byRef.keys.foreach { ref => ref ! SetRuntimeHorizon(horizon); ref ! ResumeRuntime(0L) }
        startSimulation(StartSimulationTimeEvent(startTick = start))
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
        require(phase == Loading || phase == Running || phase == Freezing || phase == Frozen, s"transfer after channel closure: $phase")
        require(event.event.tick >= gvt && event.event.tick <= horizon, "transfer outside execution frontier")
        input(processes.getOrElse(event.target, throw new IllegalArgumentException(s"unknown/retired LP ${event.target}")),
          event.event, Some(sender() -> event.token))
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
          ((phase == Freezing || phase == Frozen) && obsoleteDispatch), "schedule update after local freeze acknowledgement")
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
        phase = Committing
        eligible = Set.empty
        pending = byRef.keySet.toSet
        pending.foreach(_ ! CommitRuntime(round, gvt))
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
      case RuntimeFailure(reason) => fail(new IllegalStateException(reason))
    }
    distributed.orElse(super.handleEvent)
  }

  private def maybeFrozen(): Unit = if (phase == Freezing && pending.isEmpty && channelsDrained) {
    phase = Frozen
    global ! RuntimeFrozen(round)
  }
  private def maybeSnapshot(): Unit = if (phase == Collecting && pending.isEmpty) {
    val minimum = (nextTick.toSeq ++ runningEvents.iterator.map(a => inFlightTickOf(a.id)) ++
      snapshots.valuesIterator.flatMap(_.minimum) ++ beyondHorizon).minOption
    global ! RuntimeSnapshot(round, minimum, snapshots.valuesIterator.map(_.blocked).sum,
      snapshots.valuesIterator.map(_.callbacks).sum)
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
