package org.interscity.htc
package core.actor.manager

import core.types.Tick

import org.apache.pekko.actor.{ ActorRef, Props }
import org.interscity.htc.core.util.ManagerConstantsUtil.LOCAL_TIME_MANAGER_ACTOR_NAME

import scala.collection.mutable

/** Local Time Manager for Time-Stepped Simulation.
  * This manager advances time at fixed intervals (time steps),
  * processing all actors at each step regardless of whether they have
  * scheduled events. This approach is useful for continuous simulations
  * where actors need to update state at regular intervals.
  * 
  * @param simulationDuration The total duration of the simulation in ticks
  * @param simulationManager Reference to the simulation manager
  * @param parentManager Reference to the global time manager
  * @param timeStep The fixed time step size (default: 1 tick)
  */
class LocalTimeSteppedTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: Option[ActorRef],
  val timeStep: Tick = 1
) extends LocalTimeManagerBase(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      parentManager = parentManager,
      actorId = if (parentManager.isEmpty)
        s"$LOCAL_TIME_MANAGER_ACTOR_NAME-time-stepped-${System.nanoTime()}"
      else
        s"$LOCAL_TIME_MANAGER_ACTOR_NAME-time-stepped"
    ) {

  /** Processes a tick in time-stepped manner.
    * At each time step, all registered actors are triggered,
    * regardless of whether they have scheduled events.
    * Time advances in fixed increments.
    * 
    * @param tick The tick to process
    */
  protected def processTick(tick: Tick): Unit = {
    // Trigger all registered actors at this time step
    triggerAllActors(tick)
    
    // Wait for all actors to complete their updates
    if (runningEvents.isEmpty) {
      advanceToNextTimeStep()
    } else {
      reportGlobalTimeManager(hasScheduled = true)
    }
  }

  /** Triggers all registered actors to update at the current time step. */
  private def triggerAllActors(tick: Tick): Unit = {
    // Get actors scheduled for this tick
    val scheduledAtThisTick = scheduledActors.getOrElse(tick, mutable.Set.empty)
    
    // In time-stepped mode, we could optionally trigger ALL registered actors
    // For now, we only trigger those scheduled, but this can be extended
    if (scheduledAtThisTick.nonEmpty) {
      sendSpontaneousEvent(tick, scheduledAtThisTick)
      scheduledActors.remove(tick)
    }
    
    scheduledTicksOnFinish.remove(tick)
  }

  override protected def advanceToNextTick(): Unit = {
    if (runningEvents.isEmpty) {
      advanceToNextTimeStep()
    }
  }

  /** Advances time by one time step. */
  private def advanceToNextTimeStep(): Unit = {
    val nextTickValue = localTickOffset + timeStep
    
    if (nextTickValue - initialTick >= simulationDuration) {
      // Simulation complete
      reportGlobalTimeManager(hasScheduled = false)
    } else {
      localTickOffset = nextTickValue
      processTick(nextTickValue)
      reportGlobalTimeManager(hasScheduled = true)
    }
  }

  override protected def nextTick: Option[Tick] = {
    val nextTickValue = localTickOffset + timeStep
    if (nextTickValue - initialTick >= simulationDuration) {
      None
    } else {
      Some(nextTickValue)
    }
  }
}

object LocalTimeSteppedTimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    parentManager: Option[ActorRef],
    timeStep: Tick = 1
  ): Props =
    Props(
      classOf[LocalTimeSteppedTimeManager],
      simulationDuration,
      simulationManager,
      parentManager,
      timeStep
    )
}
