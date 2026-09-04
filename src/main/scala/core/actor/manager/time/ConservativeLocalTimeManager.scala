package org.interscity.htc
package core.actor.manager.time

import core.entity.event.control.execution.QueryNextTickEvent
import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.event.control.execution.{ LocalTimeReportEvent, UpdateGlobalTimeEvent }

/** Base for the conservative (barrier-gated) local-time-manager strategies —
  * [[LocalDiscreteEventTimeManager]]/[[LocalTimeSteppedTimeManager]] — extracted out of what used
  * to be [[LocalTimeManagerBase]] itself, with no behavior change, so an optimistic (Time Warp)
  * strategy can later share [[LocalTimeManagerBase]]'s registration/dispatch bookkeeping without
  * inheriting this barrier protocol. See `docs/TIME_WARP_DESIGN.md` §2.
  *
  * The barrier protocol: this LTM only advances once `runningEvents` empties, and every advance
  * (or newly-available schedule while idle) is reported to the `GlobalTimeManager`'s
  * `SelectiveBarrier` via a blocking `LocalTimeReportEvent` — the GTM will not tell this LTM to
  * move to the next tick until it hears back.
  */
abstract class ConservativeLocalTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: Option[ActorRef],
  actorId: String
) extends LocalTimeManagerBase(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      parentManager = parentManager,
      actorId = actorId
    ) {

  override def handleEvent: Receive = {
    val conservativeHandler: PartialFunction[Any, Unit] = {
      case e: UpdateGlobalTimeEvent => syncWithGlobalTime(e.tick)
      case QueryNextTickEvent       => reportGlobalTimeManager(hasScheduled = nextTick.isDefined)
    }
    conservativeHandler.orElse(super.handleEvent)
  }

  private def syncWithGlobalTime(globalTick: Tick): Unit = {
    if (globalTick % 1000 == 0) {
      logInfo(
        s"[LocalTM] Syncing with global tick $globalTick (previous localTick=$localTickOffset)"
      )
    }
    // If startSimulation was never called (e.g. LTM joined the pool after the
    // StartSimulationTimeEvent broadcast), initialise startTime and initialTick lazily
    // so printSimulationDuration() reports a meaningful wall-clock value instead of
    // ~Unix-epoch-ms (currentTime - 0).
    if (startTime == 0L) {
      startTime = System.currentTimeMillis()
      initialTick = globalTick
      logDebug(s"[LocalTM] startTime lazily initialised at globalTick=$globalTick (StartSimulationTimeEvent may have been missed)")
    }
    localTickOffset = globalTick
    tickOffset = globalTick - initialTick
    if (isRunning && !isTerminated) {
      // Trigger micro links before processing regular events
      triggerMicroLinks(globalTick)
      processTick(localTickOffset)
    }
  }

  /** Advances to the next simulation tick once the barrier is clear (`runningEvents` empty). */
  protected def advanceToNextTick(): Unit =
    if (runningEvents.isEmpty) {
      nextTick match {
        case Some(tick) =>
          reportGlobalTimeManager(hasScheduled = true)
        case None =>
          reportGlobalTimeManager(hasScheduled = false)
      }
    }

  // Re-notify GTM if:
  //   1. TM was idle before this actor arrived, OR
  //   2. The new actor's tick is EARLIER than the previously reported next-tick.
  // See LocalTimeManagerBase.scheduleEvent's callers for the full race-condition rationale.
  override protected def onActorRescheduled(effectiveTick: Tick, wasIdle: Boolean, isEarlierTick: Boolean): Unit =
    if ((wasIdle || isEarlierTick) && runningEvents.isEmpty) {
      logDebug(
        s"scheduleEvent: re-notifying GTM (wasIdle=$wasIdle, isEarlierTick=$isEarlierTick, tick=$effectiveTick)"
      )
      reportGlobalTimeManager(hasScheduled = true)
    }

  protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit =
    if (parentManager.nonEmpty) {
      // Report the NEXT tick we want to process (not current tick)
      val reportTick = if (hasScheduled) nextTick.getOrElse(localTickOffset) else localTickOffset
      parentManager.get ! LocalTimeReportEvent(
        tick = reportTick,
        hasScheduled = hasScheduled,
        actorRef = self.path.toString
      )
    }
}
