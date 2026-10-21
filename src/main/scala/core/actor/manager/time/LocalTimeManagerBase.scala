package org.interscity.htc
package core.actor.manager.time

import core.entity.event.control.execution.TimeManagerRegisterEvent
import core.entity.event.control.execution.QueryNextTickEvent
import core.entity.event.control.execution.RegisterPassiveActorEvent
import core.entity.event.control.execution.DestructAckEvent
import core.entity.event.control.execution.LocalDestructCompleteEvent
import core.entity.event.{ EntityEnvelopeEvent, FinishEvent, SpontaneousEvent }
import core.enumeration.CreationTypeEnum
import core.types.Tick
import core.util.{ IdUtil, StringUtil }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.*
import org.interscity.htc.core.metrics.core.{ActorMetrics, TimeManagerMetrics}

import scala.collection.mutable
import scala.concurrent.duration.*
import java.nio.file.{ Files, Paths, StandardOpenOption }
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Base abstract class for local time managers, holding only what every synchronization strategy
  * needs regardless of whether it's conservative (barrier-gated, see [[ConservativeLocalTimeManager]])
  * or optimistic (Time Warp, see `docs/TIME_WARP_DESIGN.md`): actor registration/dispatch
  * bookkeeping and the mechanics of actually sending a `SpontaneousEvent`/`DestructEvent`. It does
  * *not* implement `advanceToNextTick`/`reportGlobalTimeManager` — those encode the barrier
  * protocol itself and are strategy-specific, left abstract here for the strategy base class to
  * provide.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  * @param parentManager
  *   Reference to the global time manager
  */
abstract class LocalTimeManagerBase(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  val parentManager: Option[ActorRef],
  actorId: String
) extends TimeManagerBase(
      timeManager = null,
      actorId = actorId
    )
    with MicroAwareTimeManager {

  protected var countScheduled = 0
  private var selfProxy: ActorRef = null
  @volatile protected var isTerminated = false

  /** Bounded per-instance label for `TimeManagerMetrics.tmScheduledActors`/`tmRunningEvents` --
    * a pool routee's `self.path.name` (e.g. "$a"/"$b" under `OptimisticGlobalTimeManager`'s
    * round-robin pool) or the fixed actor name for a singleton LTM. Never an entity id (bounded by
    * the pool size, not simulation content) -- see `TimeManagerMetrics`'s doc for why this exists:
    * without it, all pool routees' writes to the same unlabeled gauge last-writer-win each other.
    */
  protected lazy val metricsManagerLabel: String = self.path.name

  /** True once this LTM's own `startSimulation` has actually run. Registration/scheduling can (and,
    * for EAGER-loaded actors, routinely does) arrive well before that -- during the load/warm-up/
    * post-load-registration phases, long before `SimulationManager` sends `StartSimulationTimeEvent`.
    * `isRunning` (`!isPaused && !isStopped`) can't distinguish "not started yet" from "running": both
    * flags default false, so it reads `true` from construction, before `startSimulation` ever fires.
    * `OptimisticLocalTimeManager.onActorRescheduled` gates its immediate dispatch on this -- without
    * it, an EAGER actor with `scheduleOnTimeManager = true` (e.g. `SubwayStation`/`BusStation`, whose
    * first `actSpontaneous` depends on cross-actor infrastructure like `Node`/the dynamic-actor-spawn
    * machinery) gets dispatched mid-load, before that infrastructure is ready, and silently does
    * nothing useful -- found via a real conservative-vs-optimistic comparison where Subway/Bus never
    * produced a single report row under Time Warp despite conservative mode running the same
    * scenario correctly. `ConservativeLocalTimeManager` never had this gap: its `onActorRescheduled`
    * doesn't dispatch anything itself, only the barrier's own `UpdateGlobalTimeEvent` round (which is
    * already correctly sequenced after startup) does.
    */
  protected var hasStarted = false
  private val registeredIdentities: mutable.Map[Long, Identify] = mutable.Map()
  private val dispatchGeneration: mutable.Map[Long, Long] = mutable.Map().withDefaultValue(0L)

  /** Actors whose latest accepted `FinishEvent` said they are waiting for a reply
    * ([[FinishEvent.awaitingReply]]). Not running and not scheduled, yet not finished either.
    */
  protected val blockedActors: mutable.Set[Long] = mutable.Set.empty

  /** Monotonically non-decreasing under every code path except one: a `ScheduleEvent` with
    * `resyncAfterRollback = true` (sent only by `SimulationBaseActor.resyncScheduleAfterRollback()`
    * after a live Time Warp rollback) unconditionally sets it to `event.tick`. No other code may
    * write a smaller value here. Safe under overlapping/repeated rollbacks of the same actor
    * because Pekko preserves per-sender→per-receiver message ordering: a later resync's `set` is
    * always processed after an earlier one's, and the actor (single-threaded) can never be
    * mid-dispatch and mid-rollback-resync at the same time.
    */
  private val highestProcessedTick: mutable.Map[Long, Tick] = mutable.Map().withDefaultValue(-1L)

  /** Actors registered via [[RegisterPassiveActorEvent]] instead of [[RegisterActorEvent]] — see
    * that event's doc for the full rationale. Never enters `scheduledActors`/`runningEvents`, so
    * it costs this LTM nothing on the normal dispatch path; its only purpose is being reachable by
    * [[forceDestructActiveActors]] at simulation end.
    */
  private val passivelyRegisteredIdentities: mutable.Map[Long, Identify] = mutable.Map()

  /** Populated by [[forceDestructActiveActors]] with every actor id it force-destructed, drained
    * as each one's [[DestructAckEvent]] arrives. Once empty, this LTM tells `parentManager` via
    * [[LocalDestructCompleteEvent]] that its whole destruct+flush cascade has actually been sent —
    * see that event's doc for why `OptimisticGlobalTimeManager` waits on it before letting
    * `ReportManager` close its writers.
    */
  private val pendingDestructAcks: mutable.Set[Long] = mutable.Set()

  /** `destruct = true` `FinishEvent`s that arrived from live business logic but haven't yet been
    * confirmed safe to act on — see `shouldDestructNow`'s doc for why. Keyed by actor id so a
    * later `ScheduleEvent` for the same actor (a rollback replaying it back to life) can cancel
    * the pending destruct by removing its entry.
    */
  private val pendingDestruct: mutable.Map[Long, FinishEvent] = mutable.Map.empty

  /** Whether a `destruct = true` `FinishEvent` can be acted on immediately. Conservative mode has
    * no speculative execution — a `FinishEvent` it sees can never be invalidated by a later
    * rollback, so the base/default is unconditionally `true` and `ConservativeLocalTimeManager`
    * must not override this. [[OptimisticLocalTimeManager]] overrides it to gate on GVT: under
    * Time Warp, an actor's "I'm done" decision is itself speculative and can be undone by a
    * rollback, so destruction must wait until GVT confirms no rollback can ever reach back past
    * `finish.end` again.
    */
  protected def shouldDestructNow(finish: FinishEvent): Boolean = true

  /** Read-only accessor onto `highestProcessedTick`, whose default (`-1L`, via `withDefaultValue`)
    * mirrors the map's own lookup semantics — used by [[OptimisticLocalTimeManager.localVirtualTime]]
    * to compute the minimum watermark across every actor currently in flight, without widening the
    * map itself to `protected`.
    */
  protected def highestProcessedTickOf(actorId: Long): Tick = highestProcessedTick(actorId)

  /** The minimum `highestProcessedTick` across every actor this LTM has ever actually dispatched
    * (via [[sendSpontaneousEvent]]) and not yet destructed (`completeDestruct` removes the entry).
    * `None` only for an LTM that has never dispatched anything yet. Unlike `highestProcessedTickOf`
    * restricted to `runningEvents`, this reflects real, already-observed progress that survives a
    * momentary gap where nothing happens to be in flight — see
    * [[OptimisticLocalTimeManager.localVirtualTime]]'s doc for why that gap matters.
    */
  protected def minHighestProcessedTick: Option[Tick] =
    if (highestProcessedTick.isEmpty) None else Some(highestProcessedTick.values.min)

  override def onStart(): Unit =
    if (parentManager.nonEmpty) {
      parentManager.get ! TimeManagerRegisterEvent(actorRef = self)
    }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterActorEvent    => registerActor(register)
    case passive: RegisterPassiveActorEvent => registerPassiveActor(passive)
    case ack: DestructAckEvent           => handleDestructAck(ack)
    case schedule: ScheduleEvent         => scheduleEvent(schedule)
    case finish: FinishEvent             => finishEvent(finish)
    case spontaneous: SpontaneousEvent   => if (isRunning) onSpontaneousEvent(spontaneous)
    case _: org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent =>
      stopSimulation()
      forceDestructActiveActors()
      terminateSimulation()
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Local TimeManager started at tick ${event.startTick}")
    startTime = System.currentTimeMillis()
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    isTerminated = false
    hasStarted = true
    self ! UpdateGlobalTimeEvent(localTickOffset)
  }

  protected def registerActor(event: RegisterActorEvent): Unit = {
    registeredActors.add(event.actorId)
    event.identify.foreach {
      identity =>
        registeredIdentities.put(event.actorId, identity)
        // Prometheus: track actor registration by type
        val actorType = identity.classType.split('.').lastOption.getOrElse(identity.classType)
        ActorMetrics.actorsRegistered.labels(actorType).inc()
        ActorMetrics.activeActors.labels(actorType).inc()
    }
    scheduleEvent(
      ScheduleEvent(tick = event.startTick, actorRef = IdUtil.format(event.actorId), identify = event.identify)
    )
  }

  /** See [[RegisterPassiveActorEvent]]'s doc: deliberately does NOT call `scheduleEvent` — this
    * actor must never receive a `SpontaneousEvent` it wasn't already going to get through its own
    * normal (interaction-driven) activity. Only makes it reachable for [[forceDestructActiveActors]].
    */
  protected def registerPassiveActor(event: RegisterPassiveActorEvent): Unit =
    event.identify.foreach {
      identity =>
        passivelyRegisteredIdentities.put(event.actorId, identity)
        val actorType = identity.classType.split('.').lastOption.getOrElse(identity.classType)
        ActorMetrics.actorsRegistered.labels(actorType).inc()
        ActorMetrics.activeActors.labels(actorType).inc()
    }

  protected def scheduleEvent(event: ScheduleEvent): Unit = {
    countScheduled += 1
    val actorId = event.identify.map(_.id).getOrElse(event.actorRef.toLong)
    // Being rescheduled means "not finished anymore" -- cancels a pending destruct if a rollback's
    // resyncScheduleAfterRollback reschedules this actor before GVT ever confirmed the destruct.
    pendingDestruct.remove(actorId)
    val effectiveTick = if (event.resyncAfterRollback) {
      logDebug(
        s"ScheduleEvent tick=${event.tick} for actor=$actorId is an authoritative rollback resync; resetting highestProcessedTick (was ${highestProcessedTick(actorId)})"
      )
      highestProcessedTick(actorId) = event.tick
      event.tick
    } else {
      val actorWatermark = highestProcessedTick(actorId)
      if (event.tick <= actorWatermark) {
        logDebug(
          s"ScheduleEvent tick=${event.tick} for actor=$actorId is at/behind its own highestProcessedTick=$actorWatermark; bumping to ${actorWatermark + 1}"
        )
        actorWatermark + 1
      } else {
        event.tick
      }
    }
    val wasIdle = scheduledActors.isEmpty && runningEvents.isEmpty
    val prevNextTick = nextTick
    val actorsSet = scheduledActors.getOrElseUpdate(effectiveTick, mutable.Set[Identify]())
    event.identify.foreach(actorsSet.add)
    val isEarlierTick = !wasIdle && prevNextTick.exists(effectiveTick < _)
    onActorRescheduled(effectiveTick, wasIdle, isEarlierTick)
  }

  /** Called after `scheduleEvent` records a new/updated schedule entry, so a strategy that needs
    * to re-notify its coordinator of newly-available work (the conservative barrier's re-notify;
    * see [[ConservativeLocalTimeManager]]) can do so. No-op by default — an optimistic strategy
    * dispatching without a barrier has nothing to re-notify here.
    */
  protected def onActorRescheduled(effectiveTick: Tick, wasIdle: Boolean, isEarlierTick: Boolean): Unit = ()

  protected def finishEvent(finish: FinishEvent): Unit =
    if (finish.timeManager == self) {
      ActorMetrics.eventsProcessed.labels("finish").inc()

      val currentGen = dispatchGeneration(finish.identify.id)
      if (!finish.destruct && finish.generation < currentGen) {
        logDebug(
          s"Dropping stale FinishEvent for ${finish.identify.id}: generation=${finish.generation} < current=$currentGen"
        )
        return
      }

      val blockedSetChanged =
        if (finish.awaitingReply) blockedActors.add(finish.identify.id)
        else blockedActors.remove(finish.identify.id)

      finish.scheduleTick.foreach {
        tickStr =>
          val tick = tickStr.toLong
          val actorWatermark = highestProcessedTick(finish.identify.id)
          val effectiveTick = if (tick <= actorWatermark) {
            logDebug(
              s"FinishEvent scheduleTick=$tick for actor=${finish.identify.id} is at/behind its own highestProcessedTick=$actorWatermark; bumping to ${actorWatermark + 1}"
            )
            actorWatermark + 1
          } else {
            tick
          }
          /** `scheduledTicksOnFinish` must track the same key `scheduledActors` was populated
            * under (`effectiveTick`), not the raw `finish.scheduleTick` -- every removal site
            * (`processTick`/`dispatchNextAvailable` across strategies) removes by `effectiveTick`.
            * Recording the raw tick here left a permanent entry behind whenever the watermark bump
            * above fired (tick <= actorWatermark, e.g. after a Time Warp rollback advanced the
            * watermark past a now-stale FinishEvent.scheduleTick): that stale tick never gets
            * removed, so `nextTick` (scheduledActors.keys ++ scheduledTicksOnFinish) never returns
            * None again, freezing `isIdle`/`localVirtualTime` forever even once all real work
            * drains.
            */
          scheduledTicksOnFinish.add(effectiveTick)
          val actorsSet = scheduledActors.getOrElseUpdate(effectiveTick, mutable.Set[Identify]())
          actorsSet.add(finish.identify)
      }

      val wasProcessingSpontaneousEvent = runningEvents.exists(_.id == finish.identify.id)
      runningEvents.filterInPlace(_.id != finish.identify.id)
      TimeManagerMetrics.tmRunningEvents.labels(metricsManagerLabel).set(runningEvents.size.toDouble)

      if (finish.scheduleTick.isEmpty) {
        val actorId = finish.identify.id
        val actorClass = finish.identify.classType
        var removedFromTicks = 0
        scheduledActors.foreach {
          case (tick, actors) =>
            val sizeBefore = actors.size
            actors.filterInPlace(_.id != actorId)
            if (actors.size < sizeBefore) removedFromTicks += 1
        }
        scheduledActors.filterInPlace {
          case (_, actors) => actors.nonEmpty
        }
        if (removedFromTicks > 0) {
//          logDebug(s"Unregistered ${actorClass} (${actorId}) from $removedFromTicks future ticks")
        }
      }

      finishDestruct(finish)
      /** Must also fire when `wasProcessingSpontaneousEvent` is false but this `FinishEvent`
        * still carried a `scheduleTick` (a duplicate/second `FinishEvent` for an actor already
        * cleared from `runningEvents` by an earlier one at the same or newer generation -- the
        * per-generation staleness guard above only rejects an *older* generation, not a second
        * delivery at the current one). Without this, the `scheduledActors`/`scheduledTicksOnFinish`
        * entry just added above sits registered but undispatched: nothing else re-triggers
        * `dispatchNextAvailable`/`advanceToNextTick` for this routee unless some other actor
        * sharing it happens to call `scheduleEvent` later, which isn't guaranteed once the
        * scenario's actor population winds down -- reproduced live: `OptimisticLocalTimeManager`
        * routees frozen with `isIdle=false` forever, `runningEvents`/`scheduledActors` genuinely
        * empty, no further `[TW-TRACE] dispatch_*` activity at all.
        */
      // A change in who is blocked changes what this LTM reports (isIdle), so it must report even when
      // nothing was running or scheduled; otherwise the GTM keeps acting on the previous report.
      if (wasProcessingSpontaneousEvent || finish.scheduleTick.isDefined || blockedSetChanged) {
        advanceToNextTick()
      }
    } else {
      finish.timeManager ! finish
    }

  private def finishDestruct(finish: FinishEvent): Unit =
    if (finish.destruct) {
      if (shouldDestructNow(finish)) completeDestruct(finish.identify)
      else pendingDestruct(finish.identify.id) = finish
    }

  /** Actually retires an actor: bookkeeping cleanup plus the shard-routed `DestructEvent` send.
    * Called either immediately (conservative mode, or optimistic mode once GVT has already caught
    * up) or later, once `drainPendingDestruct` confirms GVT has caught up to a previously-deferred
    * `FinishEvent`. Uses the shard-routed `sendDestructEvent(Identify)` overload (not the stale-
    * `ActorRef` one) so this also can't leave a stale `ActorRef` behind.
    */
  protected def completeDestruct(identity: Identify): Unit = {
    ActorMetrics.eventsProcessed.labels("destruct").inc()
    registeredActors.remove(identity.id)
    dispatchGeneration.remove(identity.id)
    highestProcessedTick.remove(identity.id)
    blockedActors.remove(identity.id)
    val removedIdentity = registeredIdentities.remove(identity.id)
    // Prometheus: decrement active actors gauge
    removedIdentity.foreach {
      removed =>
        val actorType = removed.classType.split('.').lastOption.getOrElse(removed.classType)
        ActorMetrics.activeActors.labels(actorType).dec()
    }
    sendDestructEvent(identity)
  }

  /** Confirms every `pendingDestruct` entry whose speculative `FinishEvent` is now GVT-safe
    * (`finish.end <= gvt`) and actually retires those actors; entries still ahead of GVT stay
    * pending. `pendingDestruct` is only ever populated under a strategy that overrides
    * `shouldDestructNow` to defer (i.e. [[OptimisticLocalTimeManager]]), so this is a no-op for
    * every other strategy. Defined here (rather than duplicated per-subclass) since `pendingDestruct`
    * is private to this class; subclasses drive it by calling this from wherever they learn of a
    * new GVT estimate.
    */
  protected def drainPendingDestruct(gvt: Tick): Unit =
    if (pendingDestruct.nonEmpty) {
      val (ready, stillPending) = pendingDestruct.partition { case (_, finish) => finish.end <= gvt }
      pendingDestruct.clear()
      pendingDestruct ++= stillPending
      ready.values.foreach(finish => completeDestruct(finish.identify))
    }

  override protected def onSpontaneousEvent(spontaneous: SpontaneousEvent): Unit =
    if (isRunning && !isTerminated) {
      processTick(spontaneous.tick)
    }

  /** Processes a simulation tick. Subclasses implement specific time management strategies.
    * @param tick
    *   The tick to process
    */
  protected def processTick(tick: Tick): Unit

  protected def nextTick: Option[Tick] = {
    val allTicks = scheduledActors.keys ++ scheduledTicksOnFinish

    if (allTicks.nonEmpty) {
      Some(allTicks.min)
    } else {
      None
    }
  }

  protected def processNextEventTick(tick: Tick): Unit = {
    localTickOffset = tick
    scheduledActors.get(tick).foreach {
      actorsSet =>
        logInfo(
          s"[Legacy] processNextEventTick: tick $tick with ${actorsSet.size} scheduled actors"
        )
        sendSpontaneousEvent(tick, actorsSet)
    }
    scheduledActors.remove(tick)
    scheduledTicksOnFinish.remove(tick)
  }

  protected def sendSpontaneousEvent(tick: Tick, actorsRef: mutable.Set[Identify]): Unit = {
    TimeManagerMetrics.tmScheduledActors.labels(metricsManagerLabel).set(actorsRef.size.toDouble)
    ActorMetrics.eventsProcessed.labels("spontaneous").inc(actorsRef.size.toDouble)
    actorsRef.foreach {
      identity =>
        runningEvents.add(identity)
        sendSpontaneousEvent(tick, identity)
    }
  }

  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    if (identity.actorType.isEmpty) {
      logWarn(s"Actor identity has empty actorType, removing from runningEvents to prevent stall: $identity")
      runningEvents.filterInPlace(_.id != identity.id)
      return
    }

    val generation = dispatchGeneration(identity.id) + 1
    dispatchGeneration(identity.id) = generation
    if (tick > highestProcessedTick(identity.id)) {
      highestProcessedTick(identity.id) = tick
    }

    CreationTypeEnum.valueOf(identity.actorType) match {
      case CreationTypeEnum.LoadBalancedDistributed =>
        sendSpontaneousEventShard(tick, identity, generation)
      case CreationTypeEnum.PoolDistributed =>
        sendSpontaneousEventPool(tick, identity, generation)
      case _ =>
        logWarn(s"Unknown creation type '${identity.actorType}' for actor ${identity.id}, removing from runningEvents to prevent stall")
        runningEvents.filterInPlace(_.id != identity.id)
    }
  }

  /** This LTM's last-known GVT estimate, piggybacked onto every `SpontaneousEvent` dispatch (`docs/
    * TIME_WARP_DESIGN.md` §4) so actors can flush buffered `report()` calls once it's safe. `None`
    * (meaning "no GVT concept, ignore") for every strategy except [[OptimisticLocalTimeManager]],
    * which overrides this to return its cached watermark from `GvtUpdateEvent`.
    */
  protected def currentGvt: Option[Tick] = None

  private def sendSpontaneousEventShard(tick: Tick, identity: Identify, generation: Long): Unit = {
    val actorRef = getShardRef(StringUtil.getModelClassName(identity.classType))
    actorRef ! core.entity.event.EntityEnvelopeEvent(
      IdUtil.format(identity.id),
      SpontaneousEvent(tick = tick, actorRef = self, generation = generation, gvt = currentGvt)
    )
  }

  private def sendSpontaneousEventPool(tick: Tick, identity: Identify, generation: Long): Unit = {
    val actorRef = context.system.actorSelection(identity.actorRef)
    actorRef ! SpontaneousEvent(tick = tick, actorRef = self, generation = generation, gvt = currentGvt)
  }

  private def terminateSimulation(): Unit = synchronized {
    if (!isTerminated) {
      isTerminated = true
      printSimulationDuration()
      logInfo("Local simulation terminated")
      reportGlobalTimeManager(hasScheduled = false)
    }
  }

  private def forceDestructActiveActors(): Unit = {
    val identitiesToDestruct = mutable.Map[Long, Identify]()

    // Actors whose destruct was deferred waiting for GVT confirmation (see shouldDestructNow) are
    // otherwise unreachable here entirely, same rationale as passivelyRegisteredIdentities below --
    // real simulation end must still destroy them rather than leave them waiting forever.
    pendingDestruct.foreach {
      case (id, finish) => identitiesToDestruct.put(id, finish.identify)
    }
    pendingDestruct.clear()

    scheduledActors.foreach {
      case (tick, identities) =>
        identities.foreach {
          identity =>
            identitiesToDestruct.put(identity.id, identity)
        }
    }

    runningEvents.foreach {
      identity =>
        identitiesToDestruct.put(identity.id, identity)
    }

    // §4 report-buffer flush fix (docs/TIME_WARP_DESIGN.md): actors registered passively (never
    // scheduled -- see RegisterPassiveActorEvent) are otherwise unreachable here entirely, so their
    // Time-Warp-buffered report() calls would never be flushed at simulation end.
    passivelyRegisteredIdentities.foreach {
      case (id, identity) =>
        identitiesToDestruct.put(id, identity)
    }

    if (identitiesToDestruct.nonEmpty) {
      val typeSummary = identitiesToDestruct.values
        .groupBy(id => id.classType.split('.').lastOption.getOrElse(id.classType))
        .view.mapValues(_.size).toMap
        .map { case (t, n) => s"$t=$n" }.mkString(", ")

      logInfo(
        s"Force-destructing ${identitiesToDestruct.size} active/scheduled actors on simulation stop" +
        s" (localTickOffset=$localTickOffset). Types: [$typeSummary]"
      )

      try {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = s"/tmp/htc-force-destruct-$timestamp.log"
        val tickDetail = scheduledActors.toSeq.sortBy(_._1).map { case (tick, ids) =>
          val byType = ids.groupBy(id => id.classType.split('.').lastOption.getOrElse(id.classType)).view.mapValues(_.size).toMap
          s"tick=$tick(${byType.map { case (t, n) => s"$t=$n" }.mkString(",")})"
        }.mkString("\n")
        val content = s"localTickOffset=$localTickOffset\ntypes=[$typeSummary]\n\nScheduled ticks:\n$tickDetail\n"
        Files.writeString(Paths.get(fileName), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        logInfo(s"Force-destruct details written to $fileName")
      } catch {
        case e: Exception => logWarn(s"Could not write force-destruct details to file: ${e.getMessage}")
      }
    }

    // Populate the pending-ack set before any DestructEvent is actually sent: this actor is
    // single-threaded, so no DestructAckEvent can be processed until this method returns, making
    // the set complete before it can possibly start draining.
    pendingDestructAcks.clear()
    pendingDestructAcks ++= identitiesToDestruct.keys

    identitiesToDestruct.values.foreach {
      identity =>
        if (!sendDestructEvent(identity)) {
          // Message could never be sent (unroutable/unknown actor type) -- no ack will ever come,
          // so don't wait on one.
          pendingDestructAcks -= identity.id
        }
    }

    if (pendingDestructAcks.isEmpty) {
      notifyDestructComplete()
    }
  }

  private def handleDestructAck(event: DestructAckEvent): Unit = {
    pendingDestructAcks -= event.actorId
    if (pendingDestructAcks.isEmpty) {
      notifyDestructComplete()
    }
  }

  private def notifyDestructComplete(): Unit =
    parentManager.foreach(_ ! LocalDestructCompleteEvent())

  /** Reports this LTM's progress to its coordinator. What "reports" means is strategy-specific —
    * the conservative barrier's blocking `LocalTimeReportEvent` handshake
    * (see [[ConservativeLocalTimeManager]]) vs. an eventual optimistic strategy's non-blocking LVT
    * report to a GVT coordinator (`docs/TIME_WARP_DESIGN.md` §3) — so it's left abstract here.
    */
  protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit

  private def sendDestructEvent(finishEvent: FinishEvent): Unit = {
    val actorRef = getActorRef(finishEvent.identify.actorRef)
    if (actorRef != null) {
      actorRef ! DestructEvent(actorRef = self.path.toString)
    }
  }

  /** @return whether a `DestructEvent` was actually sent -- callers use this to know whether a
    * [[DestructAckEvent]] can ever arrive for this actor (see [[forceDestructActiveActors]]).
    */
  private def sendDestructEvent(identity: Identify): Boolean = {
    if (identity.actorType.isEmpty) {
      logWarn(s"Cannot destruct actor with empty actorType: ${identity.id}")
      return false
    }

    CreationTypeEnum.valueOf(identity.actorType) match {
      case CreationTypeEnum.LoadBalancedDistributed =>
        val actorRef = getShardRef(StringUtil.getModelClassName(identity.classType))
        actorRef ! EntityEnvelopeEvent(
          IdUtil.format(identity.id),
          DestructEvent(actorRef = self.path.toString)
        )
        true
      case CreationTypeEnum.PoolDistributed =>
        val actorRef = getActorRef(identity.actorRef)
        if (actorRef != null) {
          actorRef ! DestructEvent(actorRef = self.path.toString)
          true
        } else {
          false
        }
      case _ =>
        logWarn(
          s"Unknown creation type on force-destruct: ${identity.actorType} for actor ${identity.id}"
        )
        false
    }
  }

  protected def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = self
      selfProxy
    } else {
      selfProxy
    }
}
