package org.interscity.htc
package core.runtime.accounting

import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.entity.event.control.execution.ReplaceScheduleEvent
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.apache.pekko.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent
import scala.collection.mutable
import scala.util.control.NonFatal

/** A paused-round reference implementation. All LP traffic crosses this ledger before delivery;
  * neither a mailbox estimate nor repeated idle observations can authorize commitment.
  */
final class AccountedRuntimeCoordinator(expectedManagers: Int, duration: Long, reportManager: Option[ActorRef])
  extends Actor with ActorLogging {
  require(expectedManagers > 0)
  require(duration >= 0L, "negative simulation duration")
  private val epoch = java.util.UUID.randomUUID().toString
  private case class Member(id: LogicalProcessId, ref: ActorRef, owner: ActorRef, incarnation: String, schedulerId: Long)
  private case class Delivery(target: Option[ActorRef], destination: Option[LogicalProcessId], event: AnyRef, tick: Long)
  private val managers = mutable.Set.empty[ActorRef]
  private val processes = mutable.Map.empty[LogicalProcessId, Member]
  private val retired = mutable.Set.empty[LogicalProcessId]
  private val waitingJoins = mutable.ArrayBuffer.empty[JoinRuntime]
  private val deliveries = mutable.Map.empty[Long, Delivery]
  private var beyondHorizon: Option[Long] = None
  private val reportsInTransit = mutable.Set.empty[Long]
  private val usedSinks = mutable.Set.empty[ActorRef]
  private val creations = mutable.Map.empty[(ActorRef, String), Long]
  private var token = 0L
  private var schedulerId = 0L
  private var roundId = 0L
  private var round: Option[QuiescentRound] = None
  private enum Phase { case Loading, Running, Freezing, Collecting, Committing, Retiring, Paused, Failed, Finished }
  import Phase.*
  private var phase = Loading
  private var started = false
  private var startPending: Option[Long] = None
  private var horizon = Long.MaxValue - 1L
  private var gvt = Long.MinValue
  private var pendingCommit = Set.empty[ActorRef]
  private var pendingRetire = Set.empty[ActorRef]
  private var eligibleRetire = Set.empty[ActorRef]
  private var finalCut = false
  private var truncated = false
  private var sinksDrained = false
  private var manualPause = false
  private var lastProgress = System.currentTimeMillis()
  private var lastDiagnostic = 0L


  private def members: Set[ActorRef] = managers.toSet ++ processes.values.map(_.ref)
  private def key(ref: ActorRef): String = ref.path.toStringWithAddress(self.path.address)
  private def nextToken(): Long = { token = Math.addExact(token, 1L); token }
  private def registered(ref: ActorRef): Boolean = members.contains(ref)
  private def checkEpoch(value: String): Unit = require(value == epoch, "wrong runtime epoch")

  private def fail(reason: String): Unit = if (phase != Failed && phase != Finished) {
    phase = Failed
    log.error("[RUNTIME-FAILED] {}", reason)
    members.foreach(_ ! FreezeRuntime(roundId))
    context.parent ! RuntimeFailure(reason)
  }

  override def receive: Receive = {
    case _ if phase == Failed || phase == Finished => ()
    case event =>
      try handle(event)
      catch { case NonFatal(e) => fail(s"${event.getClass.getSimpleName}: ${e.getMessage}") }
  }

  private def deliver(target: Option[ActorRef], destination: Option[LogicalProcessId], event: AnyRef, tick: Long): Unit = {
    require(tick >= gvt, s"work at $tick below committed frontier $gvt")
    if (started && tick > horizon && event.isInstanceOf[core.entity.event.ActorInteractionEvent]) {
      beyondHorizon = Some(beyondHorizon.fold(tick)(math.min(_, tick)))
      return
    }
    val id = nextToken()
    deliveries(id) = Delivery(target, destination, event, tick)
    target.foreach(_ ! RuntimeDelivery(epoch, id, event))
  }

  private def resolve(id: LogicalProcessId): Option[Member] =
    if (id.model.nonEmpty) processes.get(id)
    else {
      val matching = processes.values.filter(_.id.id == id.id).toVector
      require(matching.size <= 1, s"ambiguous untyped LP address $id")
      matching.headOption
    }

  private def join(event: JoinRuntime): Unit = {
    val id = LogicalProcessId(event.identity.classType, event.identity.id)
    require(!retired.contains(id), s"cannot resurrect committed LP $id")
    processes.get(id) match {
      case Some(existing) => require(existing.ref == event.ref && existing.incarnation == event.incarnation, s"LP incarnation changed without recovery: $id")
      case None =>
        require(!started || creations.keys.exists(_._2 == event.identity.id.toString), "unannounced LP creation after input closure")
        require(phase != Committing && phase != Retiring && phase != Finished, "creation crossed a frozen commit")
        require(event.startTick >= gvt && event.initialWake.forall(_.tick >= gvt), s"creation below frontier: $id")
        val owners = managers.toVector.sortBy(_.path.toString)
        val owner = owners(Math.floorMod(id.hashCode(), owners.size))
        schedulerId = Math.addExact(schedulerId, 1L)
        processes(id) = Member(id, event.ref, owner, event.incarnation, schedulerId)
        context.watch(event.ref)
        event.ref ! RuntimeJoined(epoch, self, owner)
        deliver(Some(owner), None, RegisterOwnedProcess(event.identity.copy(id = schedulerId), event.ref,
          event.initialWake, Some(event.identity)),
          event.initialWake.fold(math.max(0L, gvt))(_.tick))
        deliveries.toVector.foreach { case (token, d) =>
          if (d.target.isEmpty && d.destination.exists(dest => resolve(dest).exists(_.ref == event.ref))) {
            deliveries(token) = d.copy(target = Some(event.ref))
            event.ref ! RuntimeDelivery(epoch, token, d.event)
          }
        }
        if (phase == Freezing || phase == Collecting) beginRound()
    }
  }

  private def handle(event: Any): Unit = event match {
    case JoinRuntimeManager(ref) =>
      require(!started, "time-manager membership is fixed before execution")
      managers.add(ref)
      context.watch(ref)
      require(managers.size <= expectedManagers, "unexpected time-manager membership")
      if (managers.size == expectedManagers) {
        waitingJoins.toVector.foreach(join)
        waitingJoins.clear()
        startPending.foreach(tick => { startPending = None; handle(StartRuntime(tick)) })
      }
    case e: JoinRuntime =>
      require(sender() == e.ref, "LP registration sender mismatch")
      if (managers.size < expectedManagers) waitingJoins += e else join(e)
    case StartRuntime(startTick) =>
      require(!started, "runtime already started")
      if (managers.size != expectedManagers || waitingJoins.nonEmpty) {
        startPending = Some(startTick)
        return
      }
      horizon = Math.addExact(startTick, duration)
      require(horizon < Long.MaxValue, "finite horizon required")
      started = true
      phase = Running
      if (manualPause) beginRound()
      managers.foreach(m => deliver(Some(m), None, StartSimulationTimeEvent(startTick = startTick), startTick))
    case RouteInteraction(run, target, message) =>
      checkEpoch(run)
      val source = processes.values.find(_.ref == sender()).getOrElse(throw new IllegalStateException("unregistered interaction source"))
      require(message.actorRefId == source.id.id && message.actorClassType == source.id.model &&
        message.sourceEpoch == epoch && message.sourceIncarnation == source.incarnation, "interaction source identity mismatch")
      require(phase != Committing && phase != Retiring, "model send after freeze acknowledgement")
      require(!retired.exists(id => id.id == target.id && (target.model.isEmpty || id.model == target.model)), s"message to retired LP $target")
      deliver(resolve(target).map(_.ref), Some(target), message, message.tick)
      if (phase == Collecting) beginRound()
    case RouteControl(run, target, message, tick) =>
      checkEpoch(run)
      val source = processes.values.find(_.ref == sender()).getOrElse(throw new IllegalStateException("unregistered control source"))
      require(source.owner == target, "control sent to non-owner")
      require(phase != Committing && phase != Retiring, "schedule update after freeze acknowledgement")
      // Legacy manager indexes use Long keys; private handles prevent collisions between models.
      def schedulerIdentity(identity: org.htc.protobuf.core.entity.actor.Identify) = {
        require(identity.id == source.id.id, "control identity mismatch")
        identity.copy(id = source.schedulerId, classType = source.id.model)
      }
      val control = message match {
        case e: FinishEvent => e.copy(identify = schedulerIdentity(e.identify))
        case e: ReplaceScheduleEvent => e.copy(identify = schedulerIdentity(e.identify))
        case e: ScheduleEvent => e.copy(identify = Some(schedulerIdentity(e.identify.getOrElse(
          throw new IllegalArgumentException("unidentified schedule")))))
        case _ => throw new IllegalArgumentException("unsupported accounted schedule control")
      }
      deliver(Some(target), None, control, tick)
      if (phase == Collecting) beginRound()
    case RouteDispatch(target, message) =>
      require(managers.contains(sender()), "unregistered dispatch source")
      require(phase != Committing && phase != Retiring, "dispatch after freeze acknowledgement")
      val member = resolve(target).getOrElse(throw new IllegalStateException(s"dispatch to unknown LP $target"))
      require(member.owner == sender(), "dispatch from non-owner")
      require(message.tick <= horizon, "dispatch beyond execution horizon")
      deliver(Some(member.ref), Some(target), message, message.tick)
      if (phase == Collecting) beginRound()
    case DeliveryAccepted(run, id) =>
      checkEpoch(run)
      lastProgress = System.currentTimeMillis()
      if (reportsInTransit.contains(id)) {
        reportsInTransit.remove(id)
        usedSinks.add(sender())
        afterCommit()
      } else deliveries.remove(id) match {
        case Some(d) => require(d.target.contains(sender()), "delivery ACK source mismatch")
        case None => ()
      }
      maybeCollect()
    case PauseRuntimeExecution() =>
      manualPause = true
      if (started && phase == Running) beginRound()
    case ResumeRuntimeExecution() =>
      manualPause = false
      if (phase == Paused) {
        phase = Running
        members.foreach(_ ! ResumeRuntime(roundId))
      }
    case RequestRuntimeRound() =>
      if (started && phase == Running) beginRound()
      else if (started && phase != Paused && phase != Finished && phase != Failed) {
        val now = System.currentTimeMillis()
        if (now - lastProgress > 25000L && now - lastDiagnostic > 25000L) {
          lastDiagnostic = now
          log.warning(s"[RUNTIME-STALL] round=$roundId phase=$phase deliveries=${deliveries.size} commits=${pendingCommit.size} retirements=${pendingRetire.size}")
        }
      }
    case RuntimeFrozen(id) =>
      round.foreach(_.acknowledgeFrozen(key(sender()), id))
      maybeCollect()
    case snapshot: RuntimeSnapshot =>
      if (phase == Collecting) {
        round.foreach(_.report(key(sender()), snapshot))
        if (round.exists(_.complete)) commitCut()
      }
    case RuntimeCommitted(id, mayRetire) if phase == Committing && id == roundId =>
      if (pendingCommit.contains(sender())) {
        pendingCommit -= sender()
        if (mayRetire && !managers.contains(sender())) eligibleRetire += sender()
      }
      afterCommit()
    case PublishCommittedReport(target, report) =>
      require(phase == Committing && pendingCommit.contains(sender()), "report outside commit transaction")
      require(report.tick < gvt, "report is not strictly below frontier")
      val id = nextToken()
      reportsInTransit.add(id)
      target ! CommittedReportDelivery(epoch, id, report, self)
    case RuntimeProcessRetired(id) if phase == Retiring && id == roundId =>
      pendingRetire -= sender()
      processes.values.find(_.ref == sender()).foreach { member =>
        processes.remove(member.id)
        retired.add(member.id)
      }
      afterRetire()
    case RuntimeReportsFlushed(id, error) if phase == Retiring && id == roundId =>
      error match {
        case Some(reason) => fail(reason)
        case None => sinksDrained = true; afterRetire()
      }
    case BeginRuntimeCreation(id, tick) =>
      require(registered(sender()) && tick >= gvt, "invalid creation lower bound")
      require(phase != Committing && phase != Retiring, "creation after freeze")
      creations(sender() -> id) = tick
      if (phase == Collecting) beginRound()
    case EndRuntimeCreation(id) => creations.remove(sender() -> id); maybeCollect()
    case RuntimeFailure(reason) => fail(reason)
    case Terminated(ref) =>
      if (registered(ref) && !pendingRetire.contains(ref) && phase != Finished) fail(s"runtime participant lost: $ref")
    case _ => ()
  }

  private def beginRound(): Unit = {
    roundId = Math.addExact(roundId, 1L)
    round = Some(new QuiescentRound(roundId, members.map(key)))
    phase = Freezing
    members.foreach(_ ! FreezeRuntime(roundId))
    maybeCollect()
  }

  private def maybeCollect(): Unit =
    if (phase == Freezing && round.exists(_.allFrozen) && deliveries.isEmpty && creations.isEmpty) {
      phase = Collecting
      members.foreach(_ ! CollectRuntimeSnapshot(roundId))
    }

  private def commitCut(): Unit = {
    val cut = round.get
    require(deliveries.isEmpty && creations.isEmpty, "cut contains in-transit work")
    require(cut.callbacks == 0 || cut.minimum.isDefined, "unbounded asynchronous callback")
    require(cut.blocked == 0 || cut.minimum.isDefined, "blocked obligation without a time bound")
    val minimum = (cut.minimum.toSeq ++ beyondHorizon).minOption
    truncated = minimum.exists(_ > horizon)
    finalCut = (minimum.isEmpty && cut.blocked == 0 && cut.callbacks == 0) || truncated
    val candidate = if (truncated) horizon + 1L else minimum.getOrElse(Long.MaxValue)
    require(candidate >= gvt, s"safe-time regression: $candidate < $gvt")
    gvt = candidate
    context.parent ! RuntimeAdvanced(roundId, gvt)
    phase = Committing
    eligibleRetire = Set.empty
    pendingCommit = members
    members.foreach(_ ! CommitRuntime(roundId, gvt, Option.when(truncated)(horizon)))
    afterCommit()
  }

  private def afterCommit(): Unit = if (phase == Committing && pendingCommit.isEmpty && reportsInTransit.isEmpty) {
    phase = Retiring
    pendingRetire = if (finalCut) members else eligibleRetire
    sinksDrained = !finalCut
    pendingRetire.foreach { ref =>
      if (managers.contains(ref)) ref ! RuntimeStopManagers(roundId)
      else ref ! RetireRuntimeProcess(roundId, finalCut = finalCut)
    }
    if (finalCut) reportManager match {
      case Some(manager) => manager ! DrainRuntimeReports(roundId, self, usedSinks.toSet)
      case None => require(usedSinks.isEmpty, "report sinks have no drain coordinator"); sinksDrained = true
    }
    afterRetire()
  }

  private def afterRetire(): Unit = if (phase == Retiring && pendingRetire.isEmpty && sinksDrained) {
    if (finalCut) {
      phase = Finished
      context.parent ! RuntimeFinished(gvt, truncated)
    } else {
      if (manualPause) {
        phase = Paused
        context.parent ! RuntimeExecutionPaused(roundId)
      } else {
        phase = Running
        members.foreach(_ ! ResumeRuntime(roundId))
      }
    }
  }
}

object AccountedRuntimeCoordinator {
  def props(managers: Int, duration: Long, reportManager: Option[ActorRef]): Props =
    Props(new AccountedRuntimeCoordinator(managers, duration, reportManager))
}
