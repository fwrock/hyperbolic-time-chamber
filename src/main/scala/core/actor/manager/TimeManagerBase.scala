package org.interscity.htc
package core.actor.manager

import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.entity.state.DefaultState
import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ PauseSimulationEvent, RegisterActorEvent, ResumeSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent }

import scala.collection.mutable

/** Base abstract class for all time managers in the system.
  * Provides common functionality for managing simulation time, actor registration,
  * and event scheduling.
  * 
  * @param timeManager The parent time manager (if any)
  * @param actorId The unique identifier for this time manager
  */
abstract class TimeManagerBase(
  timeManager: ActorRef = null,
  actorId: String
) extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = actorId
    ) {

  // Common time manager state
  protected var startTime: Long = 0
  protected var localTickOffset: Tick = 0
  protected var tickOffset: Tick = 0
  protected var initialTick: Tick = 0
  protected var isPaused: Boolean = false
  protected var isStopped: Boolean = false

  protected val registeredActors = mutable.Set[String]()
  protected val scheduledActors = mutable.Map[Tick, mutable.Set[Identify]]()
  protected val scheduledTicksOnFinish = mutable.Set[Tick]()
  protected val runningEvents = mutable.Set[Identify]()

  /** Handles common time manager events */
  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterActorEvent    => registerActor(register)
    case schedule: ScheduleEvent         => scheduleEvent(schedule)
    case finish: FinishEvent             => finishEvent(finish)
    case spontaneous: SpontaneousEvent   => if (isRunning) onSpontaneousEvent(spontaneous)
    case _: PauseSimulationEvent         => if (isRunning) pauseSimulation()
    case _: ResumeSimulationEvent        => resumeSimulation()
    case _: StopSimulationEvent          => stopSimulation()
    case msg                             => 
      // Ignore Pekko persistence internal messages and other unhandled messages
      logDebug(s"Ignoring unhandled message: ${msg.getClass.getSimpleName}")
  }

  /** Handles spontaneous events. Subclasses override to implement specific behavior.
    * @param event The spontaneous event
    */
  protected def onSpontaneousEvent(event: SpontaneousEvent): Unit = ()

  /** Starts the simulation.
    * @param event The start simulation event
    */
  protected def startSimulation(event: StartSimulationTimeEvent): Unit

  /** Registers an actor with the time manager.
    * @param event The register actor event
    */
  protected def registerActor(event: RegisterActorEvent): Unit

  /** Schedules an event for an actor at a specific tick.
    * @param event The schedule event
    */
  protected def scheduleEvent(event: ScheduleEvent): Unit

  /** Handles the completion of an actor's spontaneous event.
    * @param event The finish event
    */
  protected def finishEvent(event: FinishEvent): Unit

  /** Pauses the simulation. */
  protected def pauseSimulation(): Unit = {
    isPaused = true
    logInfo("Simulation paused")
  }

  /** Resumes the simulation. */
  protected def resumeSimulation(): Unit = {
    isPaused = false
    logInfo("Simulation resumed")
  }

  /** Stops the simulation. */
  protected def stopSimulation(): Unit = {
    isStopped = true
    logInfo("Simulation stopped")
  }

  /** Checks if the simulation is currently running.
    * @return true if the simulation is running, false otherwise
    */
  protected def isRunning: Boolean = !isPaused && !isStopped

  /** Gets the current simulation tick.
    * @return The current tick
    */
  def getCurrentTick: Tick = localTickOffset

  /** Sends a spontaneous event to an actor.
    * @param tick The tick at which the event occurs
    * @param identity The identity of the target actor
    */
  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit

  /** Advances to the next tick in the simulation. */
  protected def advanceToNextTick(): Unit

  /** Gets the next tick to process.
    * @return The next tick, or None if simulation is complete
    */
  protected def nextTick: Option[Tick]

  /** Prints simulation duration statistics. */
  protected def printSimulationDuration(): Unit = {
    val endTime = System.currentTimeMillis()
    val duration = endTime - startTime
    logInfo(s"Simulation duration: ${duration}ms")
    logInfo(s"Simulation ticks: ${localTickOffset - initialTick}")
    logInfo(s"Average tick duration: ${duration.toDouble / (localTickOffset - initialTick)}ms")
  }
}
