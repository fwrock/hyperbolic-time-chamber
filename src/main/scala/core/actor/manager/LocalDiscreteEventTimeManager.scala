package org.interscity.htc
package core.actor.manager

import core.types.Tick

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.util.ManagerConstantsUtil.LOCAL_TIME_MANAGER_ACTOR_NAME

import scala.collection.mutable

/** Local Time Manager for Discrete Event Simulation. This manager processes events in chronological
  * order, advancing time only when all events at the current time have been processed. This is the
  * traditional approach used by the original TimeManager.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  * @param parentManager
  *   Reference to the global time manager
  */
class LocalDiscreteEventTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: Option[ActorRef]
) extends LocalTimeManagerBase(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      parentManager = parentManager,
      actorId =
        if (parentManager.isEmpty)
          s"$LOCAL_TIME_MANAGER_ACTOR_NAME-${System.nanoTime()}"
        else
          LOCAL_TIME_MANAGER_ACTOR_NAME
    ) {

  /**
   * Batch size for firing spontaneous events. When a tick has more actors
   * than this threshold, events are fired in batches to prevent overwhelming
   * the messaging system (e.g. 750K+ actors at tick 0).
   */
  private val TICK_BATCH_SIZE = 5000
  private val pendingTickActors = mutable.Queue[Identify]()
  private var currentBatchTick: Tick = -1

  /** Processes a tick in discrete event manner. Events are processed one at a time in chronological
    * order. Time only advances when all events at the current time have been processed.
    *
    * For large ticks (actors > TICK_BATCH_SIZE), events are fired in batches.
    * Each batch waits for all FinishEvents before firing the next batch.
    *
    * @param tick
    *   The tick to process
    */
  protected def processTick(tick: Tick): Unit = {
    scheduledActors.get(tick).foreach { actorsSet =>
      val actorTypes = actorsSet.groupBy(_.classType).view.mapValues(_.size).toMap
      val actorSummary = actorTypes.map {
        case (classType, count) =>
          s"${classType.split('.').lastOption.getOrElse(classType)}=$count"
      }.mkString(", ")

      if (tick % 10000 == 0 || actorsSet.size > TICK_BATCH_SIZE) {
        logInfo(
          s"[LocalDiscreteEvent] Processing tick $tick with ${actorsSet.size} scheduled actors ($actorSummary)"
        )
      }

      if (actorsSet.size <= TICK_BATCH_SIZE) {
        // Small set — fire all at once (original fast path)
        sendSpontaneousEvent(tick, actorsSet)
      } else {
        // Large set — queue and fire in batches to prevent system overload
        currentBatchTick = tick
        pendingTickActors.enqueueAll(actorsSet)
        fireNextBatch()
      }
    }
    scheduledActors.remove(tick)
    scheduledTicksOnFinish.remove(tick)

    // Only advance if nothing running and nothing pending
    if (runningEvents.isEmpty && pendingTickActors.isEmpty) {
      super.advanceToNextTick()
    }
  }

  /**
   * Fire the next batch of actors from the pending queue.
   * Resets the watchdog timestamp when starting a new batch.
   */
  private def fireNextBatch(): Unit = {
    if (runningEvents.isEmpty && pendingTickActors.nonEmpty) {
      resetWatchdogState()
    }
    var count = 0
    while (pendingTickActors.nonEmpty && count < TICK_BATCH_SIZE) {
      val identity = pendingTickActors.dequeue()
      runningEvents.add(identity)
      sendSpontaneousEvent(currentBatchTick, identity)
      count += 1
    }
    if (count > 0 && pendingTickActors.nonEmpty) {
      logInfo(
        s"[LocalTM] Fired batch of $count actors at tick $currentBatchTick, " +
          s"${pendingTickActors.size} remaining, ${runningEvents.size} running"
      )
    }
  }

  /**
   * Override to handle pending batch actors. When a batch completes
   * (runningEvents empty), fire the next batch before advancing.
   */
  override protected def advanceToNextTick(): Unit = {
    if (runningEvents.isEmpty && pendingTickActors.nonEmpty) {
      fireNextBatch()
    } else {
      super.advanceToNextTick()
    }
  }

  /** Clear pending batch actors when watchdog force-advances. */
  override protected def onWatchdogForceAdvance(): Unit = {
    if (pendingTickActors.nonEmpty) {
      logWarn(s"[Watchdog] Clearing ${pendingTickActors.size} pending batch actors")
      pendingTickActors.clear()
    }
  }
}

object LocalDiscreteEventTimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    parentManager: Option[ActorRef]
  ): Props =
    Props(
      classOf[LocalDiscreteEventTimeManager],
      simulationDuration,
      simulationManager,
      parentManager
    )
}
