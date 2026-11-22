package org.interscity.htc
package core.actor.manager.time

import core.entity.event.{FinishEvent, SpontaneousEvent}
import core.entity.event.control.execution.ReplaceScheduleEvent
import core.metrics.core.SchedulingMetrics
import core.runtime.EventOrder
import core.runtime.accounting.*
import core.runtime.conservative.*
import core.actor.manager.load.RuntimeBirthInitializer
import core.actor.manager.loadbalance.allocation.SpatialShardIdRegistry
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
  private case class Process(id: LogicalProcessId, var ref: ActorRef, identity: Identify, handle: Long,
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
  private val pendingDeliveries = mutable.Queue.empty[(Long, AnyRef)]
  private val delivering = mutable.Set.empty[Long]
  private val pendingExecution = mutable.Queue.empty[ActorRef]
  private var sendingDeliveries = false
  private var sendingGrants = false
  protected def maxInFlight: Int = 32
  protected def directInteractions: Boolean = false
  /** Optional scheduling adapters retain the same lifecycle and report barriers. */
  protected def additionalWorkPending: Boolean = false
  /** Diagnostic hook: this manager just told the coordinator it has no work pending for `round`. */
  protected def onReadyReported(round: Long): Unit = ()
  protected def additiveWakeups: Boolean = false
  protected def selectRuntimeOwner(id: LogicalProcessId, membership: Vector[ActorRef]): ActorRef =
    membership(Math.floorMod(id.hashCode(), membership.size))
  protected def validAccountingOwner(ref: ActorRef, membership: Vector[ActorRef]): Boolean =
    membership.contains(ref)
  protected def forwardDirectCompletion(owner: ActorRef, completion: DirectWorkCompletion): Unit =
    owner ! DirectAccounted(epoch, completion)
  protected def conservativeEpoch: String = epoch
  protected def conservativeMembers: Vector[ActorRef] = members
  protected def progressConservative(): Unit = {
    dispatchDueDirect()
    completeBatch()
    reportReady()
  }
  private val directLedger = new DirectWorkLedger
  private val directRoots = mutable.Queue.empty[(Process, DirectExecute)]
  private val directRootSlots = mutable.Set.empty[DirectWorkId]
  private val directProcessed = mutable.Map.empty[DirectWorkId, DirectProcessed]
  private val directBirths = mutable.Map.empty[LogicalProcessId, DirectExecute]
  private var directTick: Option[Long] = None
  private var directClosedTick: Option[Long] = None
  private var directBootstrap = false
  private var directCallbacks = 0L
  private var directChildren = 0L
  private var directForwarded = 0L
  private val reports = mutable.Set.empty[Long]
  private case object FlushDirectReports
  private var directReportFlushScheduled = false
  private lazy val directReportBatcher = new CommittedReportBatcher(
    config.getInt("htc.report-manager.delivery-batch-size"),
    () => { val id = nextToken(); reports.add(id); id },
    (target, id, events) => {
      if (events.size == 1) target ! CommittedReportDelivery(epoch, id, events.head, self)
      else target ! CommittedReportBatchDelivery(epoch, id, events, self)
    }
  )
  private val registrationFences = mutable.Map.empty[ActorRef, ActorRef]
  private val sinks = mutable.Set.empty[ActorRef]
  private var members = Vector.empty[ActorRef]
  private var epoch = ""
  private var token = 0L
  private var transfers = 0L
  private var receipts = 0L
  private var crossOwnerTransfers = 0L
  private var handle = 0L
  private var batch: Option[ExecuteConservativeBatch] = None
  private var preparing = false
  private var dispatching = false
  private var executing = Set.empty[ActorRef]
  private var stopping: Option[StopConservativeRuntime] = None
  private var retirements = Set.empty[ActorRef]
  private val pendingRetirements = mutable.Queue.empty[(ActorRef, RetireConservativeProcess)]
  private val retiringInFlight = mutable.Set.empty[ActorRef]
  private var sendingRetirements = false
  private val retirementReceipts = mutable.Map.empty[ActorRef, Long]
  private val retiredRefs = mutable.Set.empty[ActorRef]
  private val retiring = mutable.Map.empty[ActorRef, Long]
  private var failed = false
  private var readyRequest: Option[(Long, ActorRef)] = None
  private val unborn = mutable.Map.empty[LogicalProcessId, mutable.ArrayBuffer[(core.entity.event.ActorInteractionEvent, Option[(ActorRef, Long)])]]
  private val births = mutable.Map.empty[LogicalProcessId, (RuntimeBirthOrigin, RuntimeBirthData, Option[ActorRef])]
  private case class FrozenProcess(process: Process, original: ActorRef, oldPath: String, target: String,
    var replacement: Option[ActorRef] = None, var stopAuthorized: Boolean = false)
  private var migration: Option[FreezeConservativeMigration] = None
  private val frozenProcesses = mutable.Map.empty[LogicalProcessId, FrozenProcess]
  private var migrationStarted = false
  private val pendingInitialPlacements = mutable.Set.empty[ActorRef]
  private var migrationResumeTarget: Option[ActorRef] = None
  private val pendingMigrationPlacements = mutable.Set.empty[ActorRef]
  private val pendingMigrationAcks = mutable.Set.empty[ActorRef]
  protected def createRuntimeBirthInitializer(target: LogicalProcessId, origin: RuntimeBirthOrigin, data: RuntimeBirthData): ActorRef =
    context.actorOf(RuntimeBirthInitializer.props(self, coordinator, target, origin, data))

  private def nextToken(): Long = { token = Math.addExact(token, 1L); token }
  private def owner(id: LogicalProcessId): ActorRef = selectRuntimeOwner(id, members)

  private case class DelegatedBatch(token: Long, wakes: mutable.ArrayBuffer[DelegatedWakeRequest])
  private case object FlushDelegated
  private val delegatedOut = mutable.LinkedHashMap.empty[ActorRef, DelegatedBatch]
  private var delegatedFlushQueued = false
  /** Upper bound of a batch; the flush itself never waits, so this only caps one message's size. */
  protected def delegatedBatchLimit: Int = 256
  /** Receivers confirm after storage; a manager whose storage is asynchronous defers this. */
  protected def acknowledgeDelegated(to: ActorRef, token: Long): Unit =
    to ! DelegatedSchedulesAccepted(epoch, token)

  /** The fence is taken here, not at flush time, so no batch can close between enqueue and send. */
  private def delegateToOwner(destination: ActorRef, request: DelegatedWakeRequest): Unit = {
    val batch = delegatedOut.getOrElseUpdate(destination, {
      val id = nextToken()
      outgoing(id) = destination
      DelegatedBatch(id, mutable.ArrayBuffer.empty)
    })
    batch.wakes += request
    if (batch.wakes.size >= delegatedBatchLimit) flushDelegated(destination)
    else if (!delegatedFlushQueued) {
      delegatedFlushQueued = true
      self ! FlushDelegated
    }
  }
  private def flushDelegated(destination: ActorRef): Unit = delegatedOut.remove(destination).foreach { batch =>
    SchedulingMetrics.delegatedBatch.inc()
    crossOwnerTransfers += 1L
    destination ! DelegatedSchedules(epoch, batch.token, batch.wakes.toVector)
  }
  private def flushAllDelegated(): Unit = {
    delegatedFlushQueued = false
    delegatedOut.keysIterator.toVector.foreach(flushDelegated)
  }
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
    pendingDeliveries.enqueue(id -> event)
    deliverInputs()
  }
  private def deliverInputs(): Unit = if (!sendingDeliveries) {
    require(maxInFlight > 0, "conservative dispatch limit must be positive")
    sendingDeliveries = true
    try while (pendingDeliveries.nonEmpty && delivering.size < maxInFlight) {
      val (id, event) = pendingDeliveries.dequeue()
      delivering.add(id)
      accepting(id).process.ref ! ConservativeDelivery(epoch, id, event)
    } finally sendingDeliveries = false
  }
  private def grantExecution(): Unit = if (!sendingGrants && !preparing && batch.nonEmpty) {
    sendingGrants = true
    try while (pendingExecution.nonEmpty && executing.size < maxInFlight) {
      val ref = pendingExecution.dequeue()
      executing += ref
      ref ! batch.get
    } finally sendingGrants = false
  }
  private def retireProcesses(): Unit = if (!sendingRetirements) {
    sendingRetirements = true
    try while (pendingRetirements.nonEmpty && retiringInFlight.size < maxInFlight) {
      val (ref, request) = pendingRetirements.dequeue()
      retiringInFlight.add(ref)
      ref ! request
    } finally sendingRetirements = false
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
      logInfo(s"[RUNTIME-MEMBERSHIP] policy=discrete-event owner=${self.path} workers=${members.size}")
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
      pendingInitialPlacements.add(join.ref)
      byHandle(handle) = process
      context.watch(join.ref)
      registerOwnedProcess(join.identity.copy(id = handle), join.initialWake, join.identity)
      join.ref ! RuntimeJoined(epoch, coordinator, self, directConservative = directInteractions,
        additiveWakeups = additiveWakeups)
    }
    case AuthorizeRuntimeBirth(run, target, origin) => protect {
      checkEpoch(run)
      require(sender() == coordinator && births.get(target).exists(b => b._1 == origin && b._3.isEmpty), "invalid birth grant")
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
      directBirths.remove(target).foreach(processes(target).ref ! _)
      completeBatch()
    }
    case FlushDirectReports => protect {
      directReportFlushScheduled = false
      directReportBatcher.flush()
    }
    case event: DirectProcessed => protect {
      checkEpoch(event.epoch)
      require(directInteractions && validAccountingOwner(event.accountingOwner, members), "invalid direct accounting owner")
      if (!directClosedTick.exists(event.completion.work.tick <= _) && directTick.forall(_ <= event.completion.work.tick)) {
        val process = byRef.getOrElse(sender(), throw new IllegalArgumentException("unregistered direct callback"))
        require(event.completion.work.receiver == process.id, "completion from the wrong LP")
        directProcessed.get(event.completion.work.id) match {
          case Some(previous) => require(previous == event, "conflicting direct callback effects")
          case None =>
            event.completion.children.foreach(child => require(child.id.source == process.id &&
              child.id.incarnation == process.incarnation, "foreign child source incarnation"))
            event.controls.foreach(applyScheduleControl(process, _))
            event.reports.foreach(publishDirectReport)
            updateInput(process, event.next)
            process.blocked = event.blocked
            process.terminal = event.terminal
            if (event.blocked > 0) blockedProcesses.add(process.ref) else blockedProcesses.remove(process.ref)
            if (event.terminal) terminalProcesses.add(process.ref) else terminalProcesses.remove(process.ref)
            directProcessed(event.completion.work.id) = event
            directCallbacks += 1L
            directChildren += event.completion.children.size
        }
        if (event.accountingOwner == self) accountDirect(event.completion)
        else {
          dispatchDueDirect()
          directForwarded += 1L
          forwardDirectCompletion(event.accountingOwner, event.completion)
        }
        completeBatch()
      }
    }
    case DirectAccounted(run, completion) => protect {
      checkEpoch(run)
      require(directInteractions && owner(completion.work.receiver) == sender(), "foreign direct completion owner")
      if (!directClosedTick.exists(completion.work.tick <= _) && directTick.forall(_ <= completion.work.tick))
        accountDirect(completion)
    }
    case DirectBirth(run, target, delivery) if directInteractions && byRef.contains(sender()) => protect {
      checkEpoch(run)
      require(delivery.work.id.source == byRef(sender()).id, "foreign direct birth source")
      owner(target).tell(DirectBirth(run, target, delivery), self)
    }
    case DirectBirth(run, target, delivery) => protect {
      checkEpoch(run)
      require(directInteractions && owner(target) == self && members.contains(sender()), "unowned direct birth")
      require(delivery.work.receiver == target && delivery.event.isInstanceOf[core.entity.event.ActorInteractionEvent],
        "invalid direct birth envelope")
      require(!processes.contains(target) && !births.contains(target), "duplicate direct birth")
      val event = delivery.event.asInstanceOf[core.entity.event.ActorInteractionEvent]
      val data = event.data.asInstanceOf[RuntimeBirthData]
      val origin = RuntimeBirthOrigin(LogicalProcessId(event.actorClassType, event.actorRefId),
        event.order.get.key, event.tick, event.order.get.microstep)
      births(target) = (origin, data, None)
      unborn(target) = mutable.ArrayBuffer.empty
      directBirths(target) = delivery
      coordinator ! RequestRuntimeBirth(epoch, target, origin)
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
      transfers += 1L
      if (destination != self) crossOwnerTransfers += 1L
      transferTo(destination, ConservativeTransfer(epoch, id, target, event))
    }
    case FenceConservativeRegistration(run, target) => protect {
      checkEpoch(run)
      require(byRef.contains(sender()), "registration fence from unregistered LP")
      registrationFences(sender()) = target
      completeRegistrationFences()
    }
    case event: ConservativeTransfer => protect {
      checkEpoch(event.epoch)
      require(members.contains(sender()) && owner(event.target) == self, "unowned channel delivery")
      processes.get(event.target) match {
        case Some(process) =>
          require(!retiring.contains(process.ref), s"interaction to terminal LP ${event.target}")
          input(process, event.event, Some(sender() -> event.token))
        case None =>
          val queue = unborn.getOrElseUpdate(event.target, mutable.ArrayBuffer.empty)
          event.event.data match {
            case data: RuntimeBirthData =>
              val origin = RuntimeBirthOrigin(LogicalProcessId(event.event.actorClassType, event.event.actorRefId),
                event.event.order.get.key, event.event.tick, event.event.order.get.microstep)
              require(!births.contains(event.target), "duplicate child birth slot")
              births(event.target) = (origin, data, None)
              queue += event.event -> Some(sender() -> event.token)
              coordinator ! RequestRuntimeBirth(epoch, event.target, origin)
            case _ =>
              queue += event.event -> None
              sender() ! ConservativeTransferAccepted(epoch, event.token)
          }
      }
    }
    case ConservativeInputAccepted(run, id, next) => protect {
      checkEpoch(run)
      require(delivering.remove(id), "acceptance for an input not in flight")
      val accepted = accepting.remove(id).getOrElse(throw new IllegalArgumentException("unknown input acceptance"))
      require(sender() == accepted.process.ref, "input acceptance from wrong LP")
      updateInput(accepted.process, next)
      accepted.source.foreach { case (source, token) => source ! ConservativeTransferAccepted(epoch, token) }
      deliverInputs()
      executePreparedBatch()
      completeBatch()
    }
    case ConservativeTransferAccepted(run, id) => protect {
      checkEpoch(run)
      require(outgoing.remove(id).contains(sender()), "wrong channel acknowledgement")
      receipts += 1L
      completeBatch()
    }
    case FlushDelegated => protect(flushAllDelegated())
    case event: DelegatedSchedules => protect {
      checkEpoch(event.epoch)
      require(members.contains(sender()), "delegated schedule from a non-member")
      event.wakes.foreach { wake =>
        require(owner(wake.target) == self, s"delegated schedule for ${wake.target} sent to a non-owner")
        applyDelegatedWake(wake.target, wake.order, wake.revision)
      }
      acknowledgeDelegated(sender(), event.token)
    }
    case DelegatedSchedulesAccepted(run, id) => protect {
      checkEpoch(run)
      require(outgoing.remove(id).contains(sender()), "wrong delegated schedule acknowledgement")
      receipts += 1L
      completeBatch()
    }
    case RouteControl(run, target, control, _) => protect {
      checkEpoch(run)
      require(target == self, "schedule control sent to wrong owner")
      require(!hasStarted || executing.contains(sender()), "schedule update outside an authorized batch")
      val source = byRef.getOrElse(sender(), throw new IllegalArgumentException("unregistered schedule source"))
      applyScheduleControl(source, control)
    }
    case CollectConservativeReady(round) => protect {
      require(sender() == coordinator && batch.isEmpty && migration.isEmpty, "frontier requested during execution/migration")
      readyRequest = Some(round -> sender())
      require(unborn.isEmpty || births.nonEmpty, "input to an unknown/retired LP without an accounted birth")
      reportReady()
    }
    case event: ExecuteConservativeBatch => protect {
      require(sender() == coordinator && batch.isEmpty && outgoing.isEmpty && migration.isEmpty, "overlapping conservative batches/migration")
      if (directInteractions) {
        if (!directTick.contains(event.tick)) {
          require(directRoots.isEmpty && directRootSlots.isEmpty, "direct dispatch still in flight")
          directLedger.begin(event.tick)
          directProcessed.filterInPlace((_, receipt) => receipt.completion.work.tick >= event.tick)
          directTick = Some(event.tick)
        }
      }
      directBootstrap = directInteractions && event.initializationOnly
      batch = Some(event)
      preparing = true
      retireCommittedTerminals(event.round)
      localTickOffset = event.tick
      hasStarted = true
      dispatching = true
      if (!directBootstrap) dispatchConservativeSchedules(event.tick, event.microstep, directInteractions)
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
      grantExecution()
      completeBatch()
    }
    case PublishConservativeReport(target, report) => protect {
      require(byRef.contains(sender()), "report from unregistered LP")
      require(!hasStarted || executing.contains(sender()) ||
        (retirements.contains(sender()) && stopping.exists(_.finalHorizon.contains(report.tick))),
        "model report outside an authorized batch/final horizon")
      val id = nextToken()
      reports.add(id)
      target ! CommittedReportDelivery(epoch, id, report, self)
    }
    case e: PublishConservativePlacement => protect {
      checkEpoch(e.epoch)
      if (e.requestId.isEmpty) require(pendingInitialPlacements.remove(sender()), "placement outside authorized registration")
      else require(migrationResumeTarget.nonEmpty && migration.exists(_.requestId == e.requestId) &&
        pendingMigrationPlacements.remove(sender()), "placement outside authorized migration resume")
      require(byRef.contains(sender()) && e.event.entityId == byRef(sender()).id.id.toString &&
        e.event.label == "actor_placement", "invalid migration placement identity")
      e.targets.distinct.foreach { target =>
        val id = nextToken()
        reports.add(id)
        target ! CommittedReportDelivery(epoch, id, e.event, self)
      }
      completeMigrationResume()
      completeRegistrationFences()
      reportReady()
    }
    case DeliveryAccepted(run, id) => protect {
      checkEpoch(run)
      require(reports.remove(id), "unknown report receipt")
      sinks.add(sender())
      completeMigrationResume()
      completeBatch()
      completeStop()
    }
    case event: StopConservativeRuntime => protect {
      require(sender() == coordinator && batch.isEmpty && outgoing.isEmpty && accepting.isEmpty && reports.isEmpty &&
        (!directInteractions || directLedger.isClosed), "stop before worker channels drained")
      stopping = Some(event)
      retirements = byRef.keySet.toSet
      require(event.finalHorizon.isEmpty || event.truncated, "horizon finalization without truncation")
      pendingRetirements ++= retirements.iterator.map(_ -> RetireConservativeProcess(event.round, event.truncated, event.finalHorizon))
      retireProcesses()
      completeStop()
    }
    case ConservativeProcessRetired(round) => protect {
      require(retiringInFlight.contains(sender()) && !retirementReceipts.contains(sender()),
        "retirement receipt for a process not in flight")
      require(retiring.get(sender()).contains(round) ||
        (stopping.exists(_.round == round) && retirements.contains(sender())), "unexpected retirement")
      retirementReceipts(sender()) = round
      finishRetirement(sender())
    }
    case e: FreezeConservativeMigration => protect {
      checkEpoch(e.epoch)
      require(sender() == coordinator && migration.isEmpty && batch.isEmpty && outgoing.isEmpty && accepting.isEmpty &&
        reports.isEmpty && births.isEmpty && stopping.isEmpty && (!directInteractions || directLedger.isClosed),
        "migration outside a closed conservative batch")
      migration = Some(e)
      if (directInteractions) directClosedTick = directTick
      retireCommittedTerminals(e.round)
      completeMigrationFreeze()
    }
    case e: ConservativeProcessFrozen => protect {
      checkEpoch(e.epoch)
      if (migration.exists(_.requestId == e.requestId) && pendingMigrationAcks.remove(sender())) completeMigrationFreeze()
    }
    case e: RebindConservativeProcess => protect {
      checkEpoch(e.epoch)
      require(migration.exists(_.requestId == e.requestId) && pendingMigrationAcks.isEmpty, "rebind outside an acknowledged migration")
      val frozen = frozenProcesses.getOrElse(e.id, throw new IllegalArgumentException("rebind of an unfrozen LP"))
      val process = frozen.process
      require(process.incarnation == e.incarnation && frozen.oldPath == e.oldPath, "migration LP incarnation changed")
      require(frozen.stopAuthorized, "migration source stop was not authorized")
      val address = if (sender().path.address.hasLocalScope) org.apache.pekko.cluster.Cluster(context.system).selfAddress.toString
        else sender().path.address.toString
      require(address == frozen.target && sender().path.name == e.id.id.toString, "migration restored at an unplanned actor/physical node")
      require(frozen.replacement.forall(_ == sender()), "migration replacement actor changed")
      if (frozen.replacement.isEmpty) {
        val old = process.ref
        byRef.remove(old)
        pendingInitialPlacements.remove(old)
        readyInputs.remove(old).foreach(order => readyInputs(sender()) = order)
        if (blockedProcesses.remove(old)) blockedProcesses.add(sender())
        if (terminalProcesses.remove(old)) terminalProcesses.add(sender())
        context.unwatch(old)
        process.ref = sender()
        byRef(sender()) = process
        context.watch(sender())
        frozen.replacement = Some(sender())
      }
      sender() ! ConservativeProcessRebound(epoch, e.requestId, coordinator, self, process.incarnation)
    }
    case e: AuthorizeConservativeSourceStop => protect {
      checkEpoch(e.epoch)
      require(migration.exists(_.requestId == e.requestId) && pendingMigrationAcks.isEmpty,
        "source stop outside an acknowledged migration")
      val frozen = frozenProcesses.values.find(_.original == sender()).getOrElse(
        throw new IllegalArgumentException("source stop from an unfrozen LP"))
      frozen.stopAuthorized = true
      sender() ! ConservativeSourceStopAuthorized(epoch, e.requestId)
    }
    case e: ResumeConservativeMigration => protect {
      checkEpoch(e.epoch)
      require(sender() == coordinator && migration.exists(_.requestId == e.requestId) && pendingMigrationAcks.isEmpty &&
        retiring.isEmpty, "resume outside acknowledged migration")
      require(frozenProcesses.values.forall(f => !f.stopAuthorized || f.replacement.nonEmpty), "resume before source replacement")
      require(migrationResumeTarget.isEmpty, "duplicate migration resume")
      migrationResumeTarget = Some(sender())
      pendingMigrationPlacements ++= frozenProcesses.valuesIterator.map(_.process.ref)
      frozenProcesses.values.foreach(f => f.process.ref ! EndConservativeProcessMigration(epoch, e.requestId))
      completeMigrationResume()
    }
    case Terminated(ref) if retiringInFlight.contains(ref) => protect {
      retiredRefs.add(ref)
      finishRetirement(ref)
    }
    case Terminated(ref) if frozenProcesses.values.exists(f => f.original == ref && f.stopAuthorized) => ()
    case Terminated(ref) if byRef.contains(ref) && stopping.isEmpty && !retiring.contains(ref) =>
      fail(new IllegalStateException(s"LP lost: $ref"))
    case Terminated(ref) if births.valuesIterator.exists(_._3.contains(ref)) =>
      fail(new IllegalStateException(s"birth initializer lost: $ref"))
    case _: Terminated => ()
    case failure: RuntimeFailure => fail(new IllegalStateException(failure.reason))
    }
    conservative.orElse(super.handleEvent)
  }


  /** Through scheduleEvent, like the owner's own schedule: a node-local manager keeps additive wakes of its
    * own and ignores the revisioned schedule that replaceSchedule writes.
    */
  private def applyDelegatedWake(id: LogicalProcessId, order: EventOrder, revision: Long): Unit = {
    val target = processes.getOrElse(id, throw new IllegalArgumentException(
      s"delegated schedule for ${id.model}/${id.id}: target is not registered on its owner"))
    scheduleEvent(ScheduleEvent(tick = order.tick,
      identify = Some(target.identity.copy(id = target.handle, classType = target.id.model)),
      revision = revision,
      order = Some(org.htc.protobuf.core.entity.event.communication.LogicalEventOrder(order.tick, order.microstep, order.key))))
  }

  private def applyScheduleControl(source: Process, control: AnyRef): Unit = {
    def identity(value: Identify): Identify = {
      require(value.id == source.id.id, "schedule identity mismatch")
      value.copy(id = source.handle, classType = source.id.model)
    }
    control match {
      case e: ReplaceScheduleEvent =>
        SchedulingMetrics.delegated.inc()
        require(e.completedGeneration < 0L && !e.awaitingReply && e.next.nonEmpty,
          "a delegated schedule only sets a wake, it never completes the owner's dispatch")
        val target = LogicalProcessId(e.identify.classType, e.identify.id)
        val destination = owner(target)
        if (destination == self) applyDelegatedWake(target, e.next.get, e.revision)
        else delegateToOwner(destination, DelegatedWakeRequest(target, e.next.get, e.revision))
      case e: FinishEvent =>
        SchedulingMetrics.finish.inc()
        finishEvent(e.copy(identify = identity(e.identify)))
      case e: ScheduleEvent =>
        SchedulingMetrics.schedule.inc()
        scheduleEvent(e.copy(identify = Some(identity(e.identify.get))))
      case _ => throw new IllegalArgumentException("unsupported conservative schedule control")
    }
  }

  private def publishDirectReport(report: DirectReport): Unit = {
    directReportBatcher.add(report.target, report.event)
    if (directReportBatcher.nonEmpty && !directReportFlushScheduled) {
      directReportFlushScheduled = true
      self ! FlushDirectReports
    }
  }

  private def enrollDirect(process: Process, event: AnyRef, tick: Tick): Unit = {
    val work = DirectWork(DirectWorkId(process.id, process.incarnation, -nextToken()), None, process.id, tick)
    dispatchDirectRoot(process.ref, DirectExecute(epoch, work, self, event))
  }

  /** Default workers enroll and dispatch locally; node partitions can own individual roots. */
  protected def dispatchDirectRoot(target: ActorRef, delivery: DirectExecute): Unit = {
    val process = byRef(target)
    val work = delivery.work
    directLedger.enroll(work)
    directRoots.enqueue(process -> delivery)
    releaseDirectRoots()
  }

  private def releaseDirectRoots(): Unit = {
    while (directRoots.nonEmpty && directRootSlots.size < maxInFlight) {
      val (process, delivery) = directRoots.dequeue()
      directRootSlots.add(delivery.work.id)
      process.ref ! delivery
    }
  }

  private def dispatchDueDirect(): Unit =
    if (directTick.contains(localTickOffset) && !directBootstrap && hasStarted && stopping.isEmpty && migration.isEmpty)
      dispatchConservativeSchedules(localTickOffset, 0L, true)

  private def accountDirect(completion: DirectWorkCompletion): Unit = {
    directLedger.complete(completion)
    directRootSlots.remove(completion.work.id)
    releaseDirectRoots()
    dispatchDueDirect()
    completeBatch()
  }

  private def protect(body: => Unit): Unit = try body catch { case NonFatal(e) => fail(e) }
  private def completeMigrationResume(): Unit = migrationResumeTarget.foreach { target =>
    if (pendingMigrationPlacements.isEmpty && reports.isEmpty) {
      val requestId = migration.get.requestId
      frozenProcesses.clear()
      migration = None
      migrationStarted = false
      migrationResumeTarget = None
      target ! ConservativeMigrationResumed(epoch, requestId)
    }
  }

  private def completeMigrationFreeze(): Unit = migration.foreach { request =>
    if (retiring.isEmpty && !migrationStarted) {
      migrationStarted = true
      processes.values.toVector.sortBy(p => (p.id.model, p.id.id)).foreach { process =>
        SpatialShardIdRegistry.getShardId(process.id.id.toString).flatMap(request.targets.get).foreach { target =>
          require(SpatialShardIdRegistry.getEntityClassName(process.id.id.toString).contains(process.id.model),
            "live migration membership differs from published actor type")
          frozenProcesses(process.id) = FrozenProcess(process, process.ref,
            org.apache.pekko.serialization.Serialization.serializedActorPath(process.ref), target)
          pendingMigrationAcks.add(process.ref)
          process.ref ! FreezeConservativeProcess(epoch, request.requestId, if (directInteractions) directTick else None)
        }
      }
    }
    if (migrationStarted && pendingMigrationAcks.isEmpty && retiring.isEmpty) {
      coordinator ! ConservativeMigrationFrozen(epoch, request.requestId,
        frozenProcesses.values.toVector.sortBy(f => (f.process.id.model, f.process.id.id))
          .map(f => ConservativeMigrationMember(f.process.id, f.process.ref, self)))
    }
  }
  private def executePreparedBatch(): Unit = if (preparing && !dispatching && !accepting.valuesIterator.exists(_.source.isEmpty)) {
    val stage = batch.get
    preparing = false
    if (directInteractions) {
      readyInputs.toVector.foreach { case (ref, order) =>
        if (order.tick == stage.tick) {
          updateInput(byRef(ref), None)
          enrollDirect(byRef(ref), DirectQueuedInputs(stage.tick), stage.tick)
        }
      }
      releaseDirectRoots()
      completeBatch()
      return
    }
    pendingExecution ++= readyInputs.iterator.collect {
      case (ref, order) if order.tick == stage.tick && order.microstep == stage.microstep => order -> ref
    }.toVector.sortBy(_._1).map(_._2)
    grantExecution()
    completeBatch()
  }
  private def completeRegistrationFences(): Unit = if (outgoing.isEmpty && accepting.isEmpty && reports.isEmpty && !additionalWorkPending) {
    registrationFences.foreach { case (source, target) =>
      target ! core.entity.event.control.load.PostLoadRegistrationAckEvent(byRef(source).id.id.toString)
    }
    registrationFences.clear()
  }
  private def completeBatch(): Unit = {
    completeRegistrationFences()
    if (batch.nonEmpty && !preparing && !sendingGrants && pendingExecution.isEmpty && executing.isEmpty &&
      outgoing.isEmpty && accepting.isEmpty && reports.isEmpty && retiring.isEmpty && births.isEmpty && !additionalWorkPending &&
      (!directInteractions || (directLedger.isClosed && directRoots.isEmpty && directRootSlots.isEmpty))) {
      val round = batch.get.round
      batch = None
      coordinator ! ConservativeBatchComplete(round)
    } else reportReady()
  }
  private def reportReady(): Unit = if (readyRequest.nonEmpty && batch.isEmpty && outgoing.isEmpty &&
    accepting.isEmpty && reports.isEmpty && births.isEmpty && !additionalWorkPending && (!directInteractions || directLedger.isClosed)) {
    val (round, target) = readyRequest.get
    readyRequest = None
    onReadyReported(round)
    val next = (readyOrderedSchedules.map(_._3) ++ readyInputs.valuesIterator).minOption
    target ! ConservativeReady(round, next, blockedProcesses.size + runningEvents.size,
      if (directInteractions) readyInputs.valuesIterator.map(_.tick).minOption else None)
  }
  private def completeStop(): Unit = if (stopping.nonEmpty && retirements.isEmpty && reports.isEmpty) {
    if (directInteractions)
      logInfo(s"[DIRECT-ACCOUNTING] callbacks=$directCallbacks children=$directChildren forwarded=$directForwarded pending=${directLedger.pendingCount}")
    logInfo(s"[CONSERVATIVE-ACCOUNTING] round=${stopping.get.round} transfers=$transfers receipts=$receipts outgoing=${outgoing.size} accepting=${accepting.size} crossOwnerTransfers=$crossOwnerTransfers")
    coordinator ! ConservativeRuntimeStopped(stopping.get.round, sinks.toSet)
  }

  private def finishRetirement(ref: ActorRef): Unit = if (retiredRefs.contains(ref) && retirementReceipts.contains(ref)) {
    val round = retirementReceipts.remove(ref).get
    retiredRefs.remove(ref)
    retiringInFlight.remove(ref)
    // The model receipt precedes physical stop. DeathWatch closes the transport flush as well.
    if (retiring.get(ref).contains(round)) {
      retiring.remove(ref)
      pendingInitialPlacements.remove(ref)
      val process = byRef.remove(ref).get
      processes.remove(process.id)
      byHandle.remove(process.handle)
      readyInputs.remove(ref)
      blockedProcesses.remove(ref)
      terminalProcesses.remove(ref)
      forgetOwnedProcess(process.handle)
      context.unwatch(ref)
      retireProcesses()
      completeMigrationFreeze()
      completeBatch()
    } else {
      retirements -= ref
      retireProcesses()
      completeStop()
    }
  }

  private def retireCommittedTerminals(round: Long): Unit = {
    val running = runningEvents.iterator.map(_.id).toSet
    val terminal = terminalProcesses.iterator.map(byRef).filter(p => p.terminal && p.next.isEmpty && p.blocked == 0 &&
      !running.contains(p.handle)).toVector
    // A new GTM grant proves every worker drained the previous batch, including messages
    // sent by other LTMs. Local completion alone cannot authorize physical retirement.
    terminal.foreach(p => retiring(p.ref) = round)
    pendingRetirements ++= terminal.iterator.map(_.ref -> RetireConservativeProcess(round, false))
    retireProcesses()
  }

  override protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit =
    if (directInteractions) enrollDirect(byHandle(identity.id), prepareOwnedDispatch(tick, identity), tick)
    else input(byHandle(identity.id), prepareOwnedDispatch(tick, identity), None)
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
    Props(classOf[ChannelAwareLocalTimeManager], Long.box(duration), manager, coordinator)
}
