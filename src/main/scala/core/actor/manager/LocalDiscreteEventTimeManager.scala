package org.interscity.htc
package core.actor.manager

import core.types.Tick

import org.apache.pekko.actor.{ ActorRef, Props }
import org.interscity.htc.core.util.ManagerConstantsUtil.LOCAL_TIME_MANAGER_ACTOR_NAME

/** Local Time Manager for Discrete Event Simulation.
  * This manager processes events in chronological order, advancing time
  * only when all events at the current time have been processed.
  * This is the traditional approach used by the original TimeManager.
  * 
  * @param simulationDuration The total duration of the simulation in ticks
  * @param simulationManager Reference to the simulation manager
  * @param parentManager Reference to the global time manager
  */
class LocalDiscreteEventTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: Option[ActorRef]
) extends LocalTimeManagerBase(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      parentManager = parentManager,
      actorId = if (parentManager.isEmpty) 
        s"$LOCAL_TIME_MANAGER_ACTOR_NAME-${System.nanoTime()}"
      else 
        LOCAL_TIME_MANAGER_ACTOR_NAME
    ) {

  /** Processes a tick in discrete event manner.
    * Events are processed one at a time in chronological order.
    * Time only advances when all events at current tick are completed.
    * 
    * @param tick The tick to process
    */
  protected def processTick(tick: Tick): Unit = {
    if (runningEvents.isEmpty) {
      // All events at current tick completed, advance to next
      advanceToNextTick()
    } else {
      // Still processing events at current tick, wait for completion
      reportGlobalTimeManager(hasScheduled = scheduledActors.nonEmpty)
    }
  }

  override protected def advanceToNextTick(): Unit = {
    if (runningEvents.isEmpty) {
      nextTick match {
        case Some(tick) =>
          localTickOffset = tick
          processNextEventTick(tick)
          reportGlobalTimeManager(hasScheduled = true)
        case None =>
          // No more events scheduled
          reportGlobalTimeManager(hasScheduled = false)
      }
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
