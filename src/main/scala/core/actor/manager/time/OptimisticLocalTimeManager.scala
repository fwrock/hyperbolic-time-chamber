package org.interscity.htc
package core.actor.manager.time

import core.entity.event.control.execution.LvtReportEvent
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

  /** Starts immediately dispatching whatever's already scheduled instead of waiting for a
    * `SelectiveBarrier`-style `UpdateGlobalTimeEvent` push — there is no such push under this
    * strategy.
    */
  override protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Optimistic LocalTimeManager started at tick ${event.startTick}")
    startTime = System.currentTimeMillis()
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    isTerminated = false
    dispatchNextAvailable()
  }

  /** New work becoming available while idle is dispatched immediately — there's no barrier to wait
    * for permission from.
    */
  override protected def onActorRescheduled(effectiveTick: Tick, wasIdle: Boolean, isEarlierTick: Boolean): Unit =
    if (runningEvents.isEmpty) dispatchNextAvailable()

  /** Once a batch fully resolves (`runningEvents` empties), immediately dispatch the next available
    * batch — no permission round-trip — and report this LTM's new LVT.
    */
  override protected def advanceToNextTick(): Unit =
    if (runningEvents.isEmpty) {
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

  private def dispatchNextAvailable(): Unit =
    nextTick.foreach {
      tick =>
        localTickOffset = tick
        tickOffset = tick - initialTick
        processTick(tick)
    }

  /** v1 approximation of this LTM's local virtual time: the tick of whatever's currently running
    * if anything is (nothing at or after that tick can be guaranteed final yet), otherwise the
    * next scheduled tick (or the current tick if nothing is scheduled at all — the terminal case).
    * Deliberately simple; `docs/TIME_WARP_DESIGN.md` doesn't specify LVT computation at this level
    * of detail, and refining it is cheap to revisit once measured against real GVT behavior.
    */
  protected def localVirtualTime: Tick =
    if (runningEvents.nonEmpty) localTickOffset else nextTick.getOrElse(localTickOffset)

  /** Asynchronous, non-blocking LVT report — never a request the sender waits on. `hasScheduled`
    * is unused: under this strategy there's no barrier permission to request, only a status
    * update.
    */
  override protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit =
    parentManager.foreach {
      parent =>
        parent ! LvtReportEvent(
          lvt = localVirtualTime,
          isIdle = runningEvents.isEmpty && nextTick.isEmpty
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
