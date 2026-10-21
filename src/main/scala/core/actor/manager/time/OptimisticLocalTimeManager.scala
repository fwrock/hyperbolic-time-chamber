package org.interscity.htc
package core.actor.manager.time

import core.entity.event.FinishEvent
import core.entity.event.control.execution.{ GvtUpdateEvent, LvtReportEvent }
import core.types.Tick
import core.util.ManagerConstantsUtil.LOCAL_TIME_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent

/** Time Warp's local-time-manager strategy (`docs/TIME_WARP_DESIGN.md` §2): dispatches its own
  * scheduled work as soon as it's available, without waiting for the `GlobalTimeManager`'s
  * permission the way [[ConservativeLocalTimeManager]]'s `SelectiveBarrier` does. Each
  * `OptimisticLocalTimeManager` instance (recall: one instance is a cluster-router-pool routee
  * shared by many actors, same as today) runs ahead independently of every other LTM instance —
  * that cross-LTM independence, not fine-grained per-actor concurrency *within* one instance, is
  * this v1's actual parallelism win over the conservative barrier, which serializes every LTM to
  * the same GTM-chosen tick every round. Within one instance, batches are still processed
  * sequentially (`runningEvents` must empty before the next batch dispatches) — see the
  * "Known scope limits" note below for why finer-grained intra-instance concurrency isn't in v1.
  *
  * Progress is reported to the `OptimisticGlobalTimeManager` asynchronously via [[LvtReportEvent]]
  * — fire-and-forget, never a blocking handshake — so this LTM never waits on anything before
  * continuing to dispatch, matching §3's "GVT reporting must be asynchronous and non-blocking."
  *
  * '''Known scope limits (deliberately out of v1, see `docs/TIME_WARP_DESIGN.md`'s implementation
  * log entry for this step for the full rationale):'''
  *   - Straggler detection is not wired to an actual rollback trigger yet. `scheduleEvent`'s
  *     existing stale-tick guard (inherited from [[LocalTimeManagerBase]]) already detects exactly
  *     the condition Time Warp needs — a request at or behind an actor's own `highestProcessedTick`
  *     watermark — but silently bumps it forward the same way conservative mode does, rather than
  *     triggering [[core.actor.rollback.RollbackHistoryHandler]]. Wiring that handler into a real
  *     actor (`BaseActor`/`SimulationBaseActor`) and turning this into a genuine rollback trigger is
  *     deferred to land together with anti-messages (§10) — a partial rollback that can't cascade-
  *     undo what the actor already sent downstream would silently produce wrong results, which is
  *     worse than not having Time Warp at all.
  *   - Cross-actor interaction-message stragglers (an `ActorInteractionEvent` arriving with a tick
  *     behind what the receiver already processed) aren't detected here either — `handleInteractWith`
  *     bypasses the LTM entirely (interactions are actor-to-actor, not LTM-mediated) and currently
  *     just silently declines to move `currentTick` backward; making that a rollback trigger is the
  *     same deferred BaseActor-integration work as above.
  *   - Only one concrete tick-processing strategy exists (below), unlike the conservative side's
  *     discrete-event/time-stepped split — Time Warp is inherently episodic/discrete-event-shaped,
  *     so no time-stepped variant is planned.
  *   - No large-tick batching (`LocalDiscreteEventTimeManager`'s `TICK_BATCH_SIZE` mechanism) —
  *     deferred until this is measured against a real scenario at scale.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  * @param parentManager
  *   Reference to the global time manager
  */
class OptimisticLocalTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: Option[ActorRef]
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
  private var lastKnownGvt: Option[Tick] = None

  override protected def currentGvt: Option[Tick] = lastKnownGvt

  /** Under Time Warp, an actor's own "I'm done" decision is speculative -- it can be undone by a
    * later rollback -- so it's only safe to actually retire the actor once GVT has advanced past
    * the tick the decision was made at (`finish.end`); see `pendingDestruct`'s doc in
    * [[LocalTimeManagerBase]].
    */
  override protected def shouldDestructNow(finish: FinishEvent): Boolean =
    lastKnownGvt.exists(finish.end <= _)

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
    val runningIds = runningEvents.map(_.id)
    // #timewarp-trace
    core.actor.trace.TimeWarpTrace.trace(
      metricsManagerLabel,
      localTickOffset,
      "dispatch_enter",
      s"scheduledTicks=${scheduledActors.keys.toSeq.sorted.mkString(",")} runningIds=${runningIds.mkString(",")}"
    )
    var dispatchedAny = false
    scheduledActors.keys.toSeq.sorted.foreach {
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

  /** This LTM's local virtual time under per-actor concurrent dispatch (see `dispatchNextAvailable`):
    * the lowest `highestProcessedTick` watermark among every actor currently in flight — nothing at
    * or after that tick can be guaranteed final while even one in-flight actor is still working at
    * (or behind) it. Supersedes the old single-in-flight-tick approximation (`localTickOffset`
    * while anything was running), which assumed at most one tick's worth of actors could ever be
    * concurrently in flight; that stopped being true once `dispatchNextAvailable` could send
    * multiple ticks' actors out at once. Always `<=` the old approximation, so this can only make
    * LVT more conservative, never less — safe per the margin-based GVT rationale (an underestimate
    * never causes premature fossil collection or termination).
    *
    * When `runningEvents` is momentarily empty — routine between one dispatch batch resolving and
    * the next being sent, not just at genuine end-of-simulation — this must NOT fall back to
    * `localTickOffset`: that field is only ever moved *backward* by a rollback resync
    * (`resyncScheduleAfterRollback`), never forward as actors genuinely progress, so it silently
    * discards real history and pins GVT near `initialTick` forever (found 2026-09-11 — see
    * `docs/KNOWN_GAPS.md`'s "OptimisticLocalTimeManager.localVirtualTime Collapses to Near-Zero..."
    * entry for the live-run evidence: actors had genuinely processed hundreds of ticks while GVT
    * stayed plateaued at `initialTick - margin`, causing `TerminationPlateauDetector` to end the
    * simulation almost immediately). Instead, floor at `minHighestProcessedTick` — every
    * currently-registered actor's own already-observed progress, which persists across idle gaps
    * and is removed only on genuine destruct — combined with `nextTick` (not-yet-dispatched
    * scheduled work is itself a valid, even earlier, safe bound) via `min`, so this can still only
    * make LVT more conservative, never less. `localTickOffset` remains the fallback only for an LTM
    * that has never dispatched or scheduled anything at all.
    *
    * A second, independent case needs the same care: `registeredActors.isEmpty` — this LTM pool
    * routee has never had (or no longer has) a single actor assigned to it, plausible whenever the
    * pool has more instances than the scenario has actors, or the distribution is uneven (see
    * `docs/TIME_WARP_DESIGN.md`'s "an LTM pool routee that never gets any actors assigned never
    * reported its LVT" fix, which made it report at all but not what value). Such a routee can
    * never be the source of a straggler/rollback, so it must impose *no* constraint on GVT —
    * `MarginBasedGVTEstimation.estimate` takes `min` over every reporting LTM unconditionally, so a
    * permanently-empty routee reporting `localTickOffset` (`0`) would otherwise pin GVT near
    * `-margin` forever regardless of how far every other, genuinely busy LTM has progressed (found
    * 2026-09-11 alongside the gap above — fixing only the busy-LTM case did not by itself resolve a
    * real end-to-end run's premature termination; this was the other half). Reports `Long.MaxValue`
    * instead: the maximal, safest-possible LVT, since "no constraint" can only ever be an
    * *overestimate-safe* direction here — every other LTM's own real, lower LVT still bounds the
    * global `min` correctly.
    */
  protected def localVirtualTime: Tick =
    if (runningEvents.nonEmpty) {
      runningEvents.map(a => highestProcessedTickOf(a.id)).min
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
    parentManager: Option[ActorRef]
  ): Props =
    Props(
      classOf[OptimisticLocalTimeManager],
      simulationDuration,
      simulationManager,
      parentManager
    )
}
