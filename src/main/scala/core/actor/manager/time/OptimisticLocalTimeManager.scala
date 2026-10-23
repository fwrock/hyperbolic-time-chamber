package org.interscity.htc
package core.actor.manager.time

import core.entity.event.FinishEvent
import core.entity.event.control.execution.{ GvtUpdateEvent, LvtReportEvent }
import core.types.Tick
import core.util.ManagerConstantsUtil.LOCAL_TIME_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent

/** Dispatches ready LPs without the conservative barrier, at most one dispatch per LP.
  * Production dispatch and schedule control cross the accounted coordinator. Local progress
  * reports remain diagnostic and cannot authorize commitment or termination.
  */
class OptimisticLocalTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: Option[ActorRef],
  coordinator: Option[ActorRef] = None
) extends LocalTimeManagerBase(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      parentManager = parentManager,
      actorId =
        if (parentManager.isEmpty)
          s"$LOCAL_TIME_MANAGER_ACTOR_NAME-time-warp-${System.nanoTime()}"
        else
          s"$LOCAL_TIME_MANAGER_ACTOR_NAME-time-warp"
    ) {

  /** Cached from the last `GvtUpdateEvent` broadcast by the `OptimisticGlobalTimeManager` (`docs/
    * TIME_WARP_DESIGN.md` §4). `None` until the first broadcast arrives, so `currentGvt` correctly
    * reports "nothing known safe yet" instead of falsely unlocking a flush before any GVT estimate
    * has ever been computed.
    */
  accountedRuntime = coordinator
  private var lastKnownGvt: Option[Tick] = None

  override protected def currentGvt: Option[Tick] = lastKnownGvt

  /** Under Time Warp, an actor's own "I'm done" decision is speculative -- it can be undone by a
    * later rollback -- so it's only safe to actually retire the actor once GVT has advanced past
    * the tick the decision was made at (`finish.end`); see `pendingDestruct`'s doc in
    * [[LocalTimeManagerBase]].
    */
  override protected def shouldDestructNow(finish: FinishEvent): Boolean =
    lastKnownGvt.exists(finish.end < _)

  override def handleEvent: Receive = {
    val gvtHandler: PartialFunction[Any, Unit] = {
      case event: GvtUpdateEvent =>
        lastKnownGvt = Some(event.gvt)
        drainPendingDestruct(event.gvt)
    }
    gvtHandler.orElse(super.handleEvent)
  }

  /** Starts immediately dispatching whatever's already scheduled instead of waiting for a
    * `SelectiveBarrier`-style `UpdateGlobalTimeEvent` push — there is no such push under this
    * strategy.
    *
    * Always reports afterward, even when `dispatchNextAvailable()` found nothing to dispatch —
    * found running a real end-to-end scenario under Time Warp (docs/TIME_WARP_DESIGN.md): this LTM
    * pool has `HTC_TIME_MANAGER_INSTANCES` routees, and any instance that starts with zero actors
    * ever scheduled on it (plausible whenever the actor population is smaller than, or unevenly
    * distributed across, the pool) previously never sent a single `LvtReportEvent` — the only two
    * call sites were this method (only reachable via `dispatchNextAvailable`'s `nextTick.foreach`,
    * i.e. conditional on having work) and `advanceToNextTick` (only reached after `runningEvents`
    * had something in it to begin with). `OptimisticGlobalTimeManager.recomputeGvtAndCheckTermination`
    * requires a report from every registered LTM before it estimates anything at all
    * (`latestReports.size < registeredManagers.size` bails out unconditionally) — an LTM that never
    * reports means that condition is never satisfied, GVT is never computed, and the simulation can
    * never terminate, silently (no error, no log, just permanently idle). Conservative mode doesn't
    * have this gap: `ConservativeGlobalTimeManager` actively pushes `UpdateGlobalTimeEvent` to
    * every pool routee regardless of whether it has work, so an idle-since-start LTM still
    * participates in every round. Optimistic mode has no equivalent push — this one unconditional
    * report closes the gap at the one point every LTM instance is guaranteed to pass through
    * exactly once, however much (or little) work it ends up with.
    */
  override protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Optimistic LocalTimeManager started at tick ${event.startTick}")
    startTime = System.currentTimeMillis()
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    isTerminated = false
    hasStarted = true
    dispatchNextAvailable()
    reportGlobalTimeManager()
  }

  /** New work becoming available while idle is dispatched immediately — there's no barrier to wait
    * for permission from. Gated on [[hasStarted]] (see its doc): registration can arrive during the
    * load/warm-up phase, well before this LTM's own `startSimulation` has run, and dispatching then
    * would hand an EAGER actor its first `SpontaneousEvent` before the infrastructure that event's
    * handling depends on is ready. `startSimulation` itself calls `dispatchNextAvailable()`
    * unconditionally once it does fire, picking up anything that queued up early.
    */
  override protected def onActorRescheduled(effectiveTick: Tick, wasIdle: Boolean, isEarlierTick: Boolean): Unit =
    if (hasStarted) dispatchNextAvailable()

  /** Dispatches whatever's ready every time a batch resolves — no permission round-trip — and
    * reports this LTM's new LVT. Unguarded by `runningEvents.isEmpty`: `dispatchNextAvailable`
    * itself only skips actors still genuinely in flight (see its doc), so gating the call itself
    * on the whole routee being empty would resurrect the one-stuck-actor-blocks-everyone bug this
    * per-actor readiness check exists to fix.
    */
  override protected def advanceToNextTick(): Unit = {
    dispatchNextAvailable()
    reportGlobalTimeManager()
  }

  protected def processTick(tick: Tick): Unit = {
    // Process ALL events scheduled at ticks <= tick, not just exactly at tick — same skipped-tick
    // catch-up rationale as LocalDiscreteEventTimeManager.processTick (see its doc for the full
    // "orphaned actor" scenario this guards against); relevant here too since this LTM instance is
    // still a shared pool routee serving several actors' independent schedules.
    val effectiveTick = scheduledActors.keys.filter(_ <= tick).minOption.getOrElse(tick)
    if (effectiveTick < localTickOffset) {
      localTickOffset = effectiveTick
      tickOffset = effectiveTick - initialTick
    }

    scheduledActors.get(effectiveTick).foreach(actorsSet => sendSpontaneousEvent(effectiveTick, actorsSet))
    scheduledActors.remove(effectiveTick)
    scheduledTicksOnFinish.remove(effectiveTick)
  }

  /** Dispatches every actor across every scheduled tick that isn't already in flight
    * (`runningEvents`), rather than gating all dispatch on the whole routee being idle. The old
    * `nextTick.foreach` version picked one tick and dispatched everything scheduled at or before
    * it, using `runningEvents.isEmpty` at the two call sites as an implicit "nothing else is
    * running" precondition — but since one `OptimisticLocalTimeManager` instance is a shared pool
    * routee serving several causally-unrelated actors' independent schedules (see this class's own
    * doc), a single actor orphaned in `runningEvents` (e.g. by a rollback bug elsewhere) made that
    * gate permanently false and silently stalled every other actor ever scheduled on this routee —
    * confirmed live via a freeze where all pool routees were simultaneously `isIdle=false` and
    * stuck. Filtering per-actor instead means an in-flight straggler only blocks itself.
    */
  private def dispatchNextAvailable(): Unit = {
    if (runtimeFrozen) return
    val runningIds = scala.collection.mutable.Set.from(runningEvents.map(_.id))
    // #timewarp-trace
    core.actor.trace.TimeWarpTrace.trace(
      metricsManagerLabel,
      localTickOffset,
      "dispatch_enter",
      s"scheduledTicks=${scheduledActors.keys.toSeq.sorted.mkString(",")} runningIds=${runningIds.mkString(",")}"
    )
    var dispatchedAny = false
    scheduledActors.keys.toSeq.sorted.filter(tick =>
      accountedRuntime.isEmpty || tick <= Math.addExact(initialTick, simulationDuration)).foreach {
      tick =>
        scheduledActors.get(tick).foreach {
          actorsSet =>
            val ready = actorsSet.filterNot(a => runningIds.contains(a.id))
            if (ready.nonEmpty) {
              if (tick < localTickOffset) {
                localTickOffset = tick
                tickOffset = tick - initialTick
              }
              // #timewarp-trace
              core.actor.trace.TimeWarpTrace.trace(
                metricsManagerLabel,
                tick,
                "dispatch_send",
                s"dispatchedIds=${ready.map(_.id).mkString(",")} remainingAtTick=${(actorsSet.size - ready.size)}"
              )
              dispatchedAny = true
              sendSpontaneousEvent(tick, ready)
              runningIds ++= ready.map(_.id)
              actorsSet --= ready
              if (actorsSet.isEmpty) {
                scheduledActors.remove(tick)
                scheduledTicksOnFinish.remove(tick)
              }
            }
        }
    }
    // #timewarp-trace
    core.actor.trace.TimeWarpTrace.trace(
      metricsManagerLabel,
      localTickOffset,
      "dispatch_exit",
      s"dispatchedAny=$dispatchedAny remainingScheduledTicks=${scheduledActors.keys.toSeq.sorted.mkString(",")}"
    )
  }

  /** Include both queued work and actual in-flight timestamps: after rollback, a historical
    * high-water mark may be ahead of the replacement dispatch. Interaction/channel minima
    * remain outside this report and must be added before it can support a proven GVT round.
    */
  protected def localVirtualTime: Tick =
    if (runningEvents.nonEmpty) {
      (runningEvents.map(a => inFlightTickOf(a.id)) ++ nextTick).min
    } else if (registeredActors.isEmpty) {
      Long.MaxValue
    } else {
      (nextTick, minHighestProcessedTick) match {
        case (Some(scheduled), Some(floor)) => math.min(scheduled, floor)
        case (Some(scheduled), None)        => scheduled
        case (None, Some(floor))            => floor
        case (None, None)                   => localTickOffset
      }
    }

  /** Asynchronous, non-blocking LVT report — never a request the sender waits on. `hasScheduled`
    * is unused: under this strategy there's no barrier permission to request, only a status
    * update.
    */
  override protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit =
    parentManager.foreach {
      parent =>
        parent ! LvtReportEvent(
          lvt = localVirtualTime,
          isIdle = runningEvents.isEmpty && nextTick.isEmpty && blockedActors.isEmpty,
          blockedActors = blockedActors.size
        )
    }
}

object OptimisticLocalTimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    parentManager: Option[ActorRef],
    coordinator: Option[ActorRef] = None
  ): Props =
    Props(
      classOf[OptimisticLocalTimeManager],
      simulationDuration,
      simulationManager,
      parentManager,
      coordinator
    )
}
