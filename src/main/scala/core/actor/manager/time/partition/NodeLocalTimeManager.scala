package org.interscity.htc
package core.actor.manager.time.partition

import core.actor.manager.time.ChannelAwareLocalTimeManager
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.runtime.EventOrder
import core.runtime.accounting.*
import core.runtime.conservative.*
import core.runtime.conservative.partition.*
import core.types.Tick
import org.apache.pekko.actor.{ ActorRef, Props, Terminated }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import scala.collection.mutable
import scala.util.control.NonFatal

/** Stable node authority with per-tick event assignment to private local worker partitions.
  * Existing registration, input, reporting and retirement barriers remain on the node authority.
  */
class NodeLocalTimeManager(
  duration: Tick,
  manager: ActorRef,
  coordinator: ActorRef,
  workerCount: Int
) extends ChannelAwareLocalTimeManager(duration, manager, coordinator) {
  private val assignments = new TickScheduleAssignments(workerCount)
  private val identities = mutable.LongMap.empty[Identify]
  private val handles = mutable.Map.empty[LogicalProcessId, Long]
  private val owners = mutable.Map.empty[LogicalProcessId, ActorRef]
  private val cohorts = mutable.Map.empty[ActorRef, Vector[ActorRef]]
  private val earlyCohorts = mutable.Map.empty[ActorRef, PartitionCohort]
  private val pendingRoots = mutable.Map.empty[DirectWorkId, ActorRef]
  private val drains = mutable.LongMap.empty[ActorRef]
  private val granted = mutable.Set.empty[ActorRef]
  private val forwarded = mutable.Queue.empty[(ActorRef, DirectWorkCompletion)]
  private val delegatedAcks = mutable.Queue.empty[(ActorRef, Long)]
  private var partitions = Vector.empty[ActorRef]
  private var activeTick: Option[Long] = None
  private var bootstrap = false
  private var nextDrain = 0L
  private var bootstrapSequence = 0L
  private var adapterFailed = false

  // Bounded protocol trace, dumped on any adapter failure. Per-wake/per-root events are only counted:
  // tracing them individually would put string building on the hottest path of the scheduler.
  private val TraceCapacity = 256
  private val SummaryEveryTicks = 1000L
  private val trace = new java.util.ArrayDeque[String](TraceCapacity + 1)
  private val traceStartNanos = System.nanoTime()
  private var wakesStored, wakesClaimed, rootsDispatched, rootsSettled, drainsSent, drainsAcked, ticksGranted =
    0L

  private def counters: String =
    s"stored=$wakesStored claimed=$wakesClaimed rootsDispatched=$rootsDispatched rootsSettled=$rootsSettled " +
      s"drainsSent=$drainsSent drainsAcked=$drainsAcked"

  private def note(entry: => String): Unit = {
    if (trace.size >= TraceCapacity) trace.removeFirst()
    trace.addLast(s"+${(System.nanoTime() - traceStartNanos) / 1000000L}ms $entry")
  }

  private def pendingState: String =
    s"active=$activeTick bootstrap=$bootstrap pendingRoots=${pendingRoots.size} drains=${drains.size} " +
      s"pendingAcceptance=${assignments.pendingAcceptanceCount} forwarded=${forwarded.size} " +
      s"granted=${granted.size}/$workerCount $counters " +
      s"sampleRoots=${pendingRoots.take(5).map { case (id, w) => s"${id.source}#${id.sequence}@${w.path.name}" }.mkString("[", ",", "]")} " +
      s"sampleDrains=${drains.take(5).map { case (token, w) => s"$token@${w.path.name}" }.mkString("[", ",", "]")}"

  private def dumpTrace(reason: String): Unit =
    logError(
      s"[NODE-LOCAL-TRACE] ${self.path.name} $reason | $pendingState | last ${trace.size} protocol events, oldest first:\n" +
        trace.toArray.mkString("\n")
    )

  override protected def directInteractions: Boolean = true

  override protected def onReadyReported(round: Long): Unit =
    note(s"READY-REPORTED round=$round $pendingState")
  override protected def additiveWakeups: Boolean = true

  /** Register only after all local worker references exist; membership itself is fixed. */
  override def onStart(): Unit = {
    partitions = Vector.tabulate(workerCount) {
      index =>
        val ref = context.actorOf(TickPartitionWorker.props(self, maxInFlight), s"partition-$index")
        context.watch(ref)
        ref
    }
    super.onStart()
  }

  override protected def selectRuntimeOwner(
    id: LogicalProcessId,
    membership: Vector[ActorRef]
  ): ActorRef =
    owners.getOrElse(id, membership(Math.floorMod(id.hashCode(), membership.size)))

  override protected def validAccountingOwner(
    ref: ActorRef,
    membership: Vector[ActorRef]
  ): Boolean =
    cohorts.valuesIterator.exists(_.contains(ref))

  override protected def additionalWorkPending: Boolean =
    cohorts.size != conservativeMembers.size || conservativeMembers.isEmpty ||
      assignments.hasPendingAcceptance || pendingRoots.nonEmpty || drains.nonEmpty ||
      forwarded.nonEmpty || (!bootstrap && activeTick.exists(
        tick => assignments.dueOwners(tick).nonEmpty
      ))

  /** Hold completion until newly scheduled effects have destination storage receipts. */
  override protected def forwardDirectCompletion(
    owner: ActorRef,
    completion: DirectWorkCompletion
  ): Unit = {
    forwarded.enqueue(owner -> completion)
    flushCompletions()
  }

  /** A delegated wake is only confirmed once every wake admitted so far has a storage receipt. */
  override protected def acknowledgeDelegated(to: ActorRef, token: Long): Unit = {
    delegatedAcks.enqueue(to -> token)
    flushCompletions()
  }

  private def flushCompletions(): Unit = if (!assignments.hasPendingAcceptance) {
    while (forwarded.nonEmpty) {
      val (owner, completion) = forwarded.dequeue()
      owner ! DirectAccounted(conservativeEpoch, completion)
    }
    while (delegatedAcks.nonEmpty) {
      val (to, token) = delegatedAcks.dequeue()
      to ! DelegatedSchedulesAccepted(conservativeEpoch, token)
    }
  }

  override protected def registerOwnedProcess(
    identity: Identify,
    wake: Option[EventOrder],
    logicalIdentity: Identify
  ): Unit = {
    identities(identity.id) = identity
    handles(LogicalProcessId(logicalIdentity.classType, logicalIdentity.id)) = identity.id
    super.registerOwnedProcess(identity, wake, logicalIdentity)
  }

  /** Additive wake-ups are keyed by typed node handle/tick, independently of replacement revisions.
    */
  override protected def scheduleEvent(event: ScheduleEvent): Unit = {
    require(!event.resyncAfterRollback, "node-local scheduling does not support rollback")
    val identity = identities(event.identify.get.id)
    require(activeTick.forall(event.tick >= _), "schedule below node-local frontier")
    val previous = highestProcessedTickOf(identity.id)
    val effectiveTick = if (event.tick <= previous) Math.addExact(previous, 1L) else event.tick
    val wake = TickWake(effectiveTick, identity)
    val (index, fresh) = assignments.admit(wake)
    if (fresh) partitions(index) ! StoreWake(conservativeEpoch, wake)
  }

  /** Finish releases only its dispatch generation; existing future wake-ups remain intact. */
  override protected def finishEvent(event: FinishEvent): Unit = {
    require(event.timeManager == self, "finish addressed a foreign node")
    releaseDispatch(event.identify.id, event.generation)
    if (!event.discardedDispatch) event.scheduleTick.foreach {
      tick =>
        scheduleEvent(ScheduleEvent(tick.toLong, identify = Some(identities(event.identify.id))))
    }
  }

  override protected def readyOrderedSchedules: Vector[(Tick, Identify, EventOrder)] =
    assignments.earliest.map(
      wake =>
        (
          wake.tick,
          wake.identity,
          EventOrder.root(wake.tick, wake.identity.classType, wake.identity.id)
        )
    )

  /** A grant reaches every partition once. Workers continue bounded claims as slots become free. */
  override protected def dispatchConservativeSchedules(
    tick: Tick,
    microstep: Long,
    allMicrosteps: Boolean
  ): Unit =
    partitions.filterNot(granted.contains).foreach {
      worker =>
        nextDrain = Math.addExact(nextDrain, 1L)
        drains(nextDrain) = worker
        drainsSent += 1
        note(s"DRAIN-SENT token=$nextDrain worker=${worker.path.name} tick=$tick micro=$microstep all=$allMicrosteps")
        granted.add(worker)
        worker ! DrainPartition(conservativeEpoch, nextDrain, tick)
    }

  override protected def dispatchDirectRoot(target: ActorRef, delivery: DirectExecute): Unit = {
    val wake = delivery.event match {
      case _: SpontaneousEvent =>
        Some(TickWake(delivery.work.tick, identities(handles(delivery.work.receiver))))
      case _ => None
    }
    val index = wake.map(assignments.owner).getOrElse {
      val index = (bootstrapSequence % workerCount).toInt
      bootstrapSequence = Math.addExact(bootstrapSequence, 1L)
      index
    }
    val worker = partitions(index)
    pendingRoots(delivery.work.id) = worker
    rootsDispatched += 1
    if (pendingRoots.size == 1) note(s"ROOT-FIRST tick=${delivery.work.tick} id=${delivery.work.id.source}#${delivery.work.id.sequence} worker=${worker.path.name} wake=${wake.isDefined}")
    worker ! StartPartitionRoot(
      conservativeEpoch,
      target,
      delivery.copy(accountingOwner = worker),
      wake
    )
  }

  /** Partition ACKs are ordinary runtime control; modeled sends retain tracked direct transport. */
  override def handleEvent: Receive = {
    val inherited = super.handleEvent
    val partition: Receive = {
      case _ if adapterFailed => ()
      case event: ConservativeMembership =>
        guarded {
          inherited(event)
          partitions.foreach(_ ! ConfigurePartition(event.epoch, event.managers))
          event.managers.foreach(_ ! PartitionCohort(event.epoch, partitions))
          val pending = earlyCohorts.toVector
          earlyCohorts.clear()
          pending.foreach { case (from, cohort) => acceptCohort(from, cohort) }
        }
      case event: PartitionOwners =>
        guarded {
          require(
            sender() == coordinator && event.epoch == conservativeEpoch &&
              conservativeMembers.contains(event.owner),
            "invalid node-local owner publication"
          )
          require(owners.get(event.id).forall(_ == event.owner), "node-local LP ownership changed")
          owners(event.id) = event.owner
        }
      case event: PartitionCohort =>
        guarded {
          // Membership comes from the coordinator, cohorts from peers: sender FIFO cannot order them.
          if (conservativeEpoch.isEmpty) {
            require(earlyCohorts.get(sender()).forall(_ == event), "partition cohort changed before membership")
            earlyCohorts(sender()) = event
          } else acceptCohort(sender(), event)
        }
      case event: ExecuteConservativeBatch =>
        guarded {
          require(sender() == coordinator, "foreign node-local grant")
          bootstrap = event.initializationOnly
          if (!activeTick.contains(event.tick)) {
            note(s"GRANT tick=${event.tick} micro=${event.microstep} init=${event.initializationOnly} prev=$activeTick $pendingState")
            require(
              pendingRoots.isEmpty && drains.isEmpty && !assignments.hasPendingAcceptance,
              s"node-local advance before settlement: grant=${event.tick} $pendingState"
            )
            assignments.advance(event.tick)
            activeTick = Some(event.tick)
            granted.clear()
            ticksGranted += 1
            if (ticksGranted % SummaryEveryTicks == 0)
              logInfo(s"[NODE-LOCAL] ${self.path.name} tick=${event.tick} ticksGranted=$ticksGranted $counters")
          } else note(s"GRANT-SAME-TICK tick=${event.tick} micro=${event.microstep} init=${event.initializationOnly}")
          inherited(event)
        }
      case WakeStored(run, wake) =>
        guarded {
          check(run)
          wakesStored += 1
          assignments.accept(wake, indexOf(sender()))
          flushCompletions()
          progressConservative()
        }
      case WakeClaimed(run, wake) =>
        guarded {
          check(run)
          require(activeTick.contains(wake.tick), "claim outside authorized tick")
          wakesClaimed += 1
          assignments.claim(wake, indexOf(sender()))
          runningEvents.add(wake.identity)
          sendSpontaneousEvent(wake.tick, wake.identity)
        }
      case PartitionDrained(run, token) =>
        guarded {
          check(run)
          drainsAcked += 1
          note(s"DRAINED token=$token worker=${sender().path.name}")
          require(drains.remove(token).contains(sender()), "unexpected partition grant receipt")
          progressConservative()
        }
      case PartitionRootsSettled(run, roots) =>
        guarded {
          check(run)
          require(
            roots.nonEmpty && roots.distinct.size == roots.size,
            "invalid settled root cohort"
          )
          rootsSettled += roots.size
          note(s"ROOTS-SETTLED n=${roots.size} worker=${sender().path.name} remaining=${pendingRoots.size - roots.size}")
          roots.foreach(
            id => require(pendingRoots.remove(id).contains(sender()), "foreign root settlement")
          )
          progressConservative()
        }
      case Terminated(ref) if partitions.contains(ref) =>
        guarded {
          throw new IllegalStateException(s"node-local partition lost: $ref")
        }
      case _: FreezeConservativeMigration =>
        guarded {
          throw new IllegalStateException("node-local-tick does not support live migration")
        }
    }
    partition.orElse(inherited)
  }

  private def acceptCohort(from: ActorRef, event: PartitionCohort): Unit = {
    check(event.epoch)
    require(
      conservativeMembers.contains(from) && event.workers.nonEmpty &&
        event.workers.distinct.size == event.workers.size && event.workers.forall(_.path.parent == from.path),
      "invalid partition cohort"
    )
    require(cohorts.get(from).forall(_ == event.workers), "partition cohort changed")
    cohorts(from) = event.workers
    progressConservative()
  }

  private def check(run: String): Unit =
    require(run == conservativeEpoch && run.nonEmpty, "wrong node-local epoch")

  private def indexOf(worker: ActorRef): Int = {
    val index = partitions.indexOf(worker)
    require(index >= 0, "unknown local partition")
    index
  }

  private def guarded(body: => Unit): Unit = try body
  catch {
    case NonFatal(error) =>
      adapterFailed = true
      dumpTrace(s"failure: ${error.getMessage}")
      coordinator ! RuntimeFailure(s"node-local scheduler ${self.path}: ${error.getMessage}")
  }
}

object NodeLocalTimeManager {

  /** Constructor arguments remain serializable for one representative deployed on each JVM. */
  def props(duration: Tick, manager: ActorRef, coordinator: ActorRef, workers: Int): Props =
    Props(classOf[NodeLocalTimeManager], Long.box(duration), manager, coordinator, Int.box(workers))
}
