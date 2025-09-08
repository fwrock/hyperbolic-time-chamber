package org.interscity.htc
package core.actor.manager.base

import core.entity.event.{EntityEnvelopeEvent, FinishEvent, SpontaneousEvent}
import core.types.Tick
import core.entity.state.DefaultState
import core.actor.manager.BaseManager

import org.apache.pekko.actor.{ActorRef, Props}
import org.htc.protobuf.core.entity.event.control.execution.*
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum
import org.interscity.htc.core.util.StringUtil.getModelClassName

import scala.collection.mutable

/**
 * Abstract base class for all Time Manager implementations in the Hyperbolic Time Chamber simulation system.
 * 
 * This class provides common functionality shared between Global Time Managers and Local Time Managers,
 * including simulation lifecycle management, event handling, and actor coordination utilities.
 * 
 * The BaseTimeManager extends BaseManager and implements the foundation for distributed time management
 * across different simulation paradigms (DES, Time-Stepped, Optimistic).
 * 
 * @param actorId The unique identifier for this time manager actor
 * @param timeManager Reference to the parent time manager (null for GlobalTimeManager)
 * @param simulationDuration Maximum duration of the simulation in ticks
 * @param simulationManager Reference to the main simulation manager
 * 
 * @author Hyperbolic Time Chamber Team
 * @version 1.4.0
 * @since 1.0.0
 */
abstract class BaseTimeManager(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  actorId: String
) extends BaseManager[DefaultState](
      timeManager = null,
      actorId = actorId
    ) {

  /**
   * Start time of the simulation in system milliseconds.
   * Used for performance monitoring and duration calculations.
   */
  protected var startTime: Long = 0

  /**
   * Current local time offset in simulation ticks.
   * Represents the progression of simulation time for this manager.
   */
  protected var localTickOffset: Tick = 0

  /**
   * Additional tick offset for fine-grained time management.
   * Used for coordination between different time management strategies.
   */
  protected var tickOffset: Tick = 0

  /**
   * Initial tick value when simulation started.
   * Baseline for calculating relative simulation progress.
   */
  protected var initialTick: Tick = 0

  /**
   * Flag indicating if the simulation is currently paused.
   * When true, event processing is suspended but can be resumed.
   */
  protected var isPaused: Boolean = false

  /**
   * Flag indicating if the simulation has been stopped.
   * When true, the simulation cannot be resumed and will terminate.
   */
  protected var isStopped: Boolean = false

  /**
   * Set of registered actor IDs managed by this time manager.
   * Contains unique identifiers for all actors under this manager's coordination.
   */
  protected val registeredActors = mutable.Set[String]()

  /**
   * Main event handler that combines common and specific event processing.
   * 
   * This method routes events to either the common receive handler (for events
   * shared by all time managers) or the specific receive handler (for events
   * unique to each time manager implementation).
   * 
   * @return Partial function that handles all incoming events
   */
  override def handleEvent: Receive = commonReceive.orElse(specificReceive)

  /**
   * Common event receiver for all Time Manager types.
   * 
   * Handles simulation lifecycle events that are universal across all time management
   * strategies, including start, pause, resume, stop, and spontaneous events.
   * 
   * @return Partial function for handling common simulation events
   */
  private def commonReceive: Receive = {
    case start: StartSimulationTimeEvent => handleStartSimulation(start)
    case PauseSimulationEvent => if (isRunning) pauseSimulation()
    case ResumeSimulationEvent => resumeSimulation()
    case StopSimulationEvent => stopSimulation()
    case spontaneous: SpontaneousEvent => if (isRunning) actSpontaneous(spontaneous)
  }

  /**
   * Specific event receiver for each Time Manager implementation.
   * 
   * This abstract method must be implemented by each concrete time manager
   * to handle events specific to their time management strategy (DES, Time-Stepped, etc.).
   * 
   * @return Partial function for handling implementation-specific events
   */
  protected def specificReceive: Receive

  // ==================== SIMULATION LIFECYCLE METHODS ====================

  /**
   * Handles the start simulation event.
   * 
   * Initializes the simulation state, sets running flags, processes any stashed messages,
   * and delegates to the specific implementation's start logic. Extracts start time from
   * the event data or uses current system time as fallback.
   * 
   * @param start Event containing simulation start parameters including start tick and optional start time
   */
  protected def handleStartSimulation(start: StartSimulationTimeEvent): Unit = {
    logInfo(s"Starting simulation: ${start.startTick}")
    unstashAll()
    
    start.data match {
      case Some(data) => startTime = data.startTime
      case _ => startTime = System.currentTimeMillis()
    }
    
    initialTick = start.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    
    onSimulationStart(start)
  }

  /**
   * Pauses the simulation execution.
   * 
   * Sets the paused flag to true, logs the pause event, and calls the
   * implementation-specific pause handler. Events will be stashed until resumed.
   */
  protected def pauseSimulation(): Unit = {
    isPaused = true
    logInfo("Paused simulation")
    onSimulationPause()
  }

  /**
   * Resumes the simulation execution.
   * 
   * If the simulation is currently paused, this method clears the pause flag,
   * sends a spontaneous event to restart processing, and calls the implementation-specific
   * resume handler.
   */
  protected def resumeSimulation(): Unit = {
    if (isPaused) {
      isPaused = false
      logInfo("Resumed simulation")
      self ! SpontaneousEvent(tick = localTickOffset, actorRef = self)
      onSimulationResume()
    }
  }

  /**
   * Stops the simulation execution permanently.
   * 
   * Sets the stopped flag, logs the stop event, calls the implementation-specific
   * stop handler, and initiates simulation termination.
   */
  protected def stopSimulation(): Unit = {
    isStopped = true
    logInfo("Stopped simulation")
    onSimulationStop()
    terminateSimulation()
  }

  /**
   * Terminates the simulation and stops the actor.
   * 
   * If no actors are registered (all have been cleaned up), prints the
   * simulation duration statistics and stops this actor.
   */
  protected def terminateSimulation(): Unit = {
    if (registeredActors.isEmpty) {
      printSimulationDuration()
      context.stop(self)
    }
  }

  /**
   * Prints simulation duration and performance statistics.
   * 
   * Calculates and logs the total simulation duration, final time,
   * and number of registered actors for performance analysis.
   */
  protected def printSimulationDuration(): Unit = {
    val duration = System.currentTimeMillis() - startTime
    logInfo(s"${getLabel} - Finished simulation:")
    logInfo(s"  Finish time: $localTickOffset")
    logInfo(s"  Total duration: ${duration}ms")
    logInfo(s"  Registered actors: ${registeredActors.size}")
  }

  /**
   * Checks if the simulation is currently running.
   * 
   * @return true if the simulation is neither paused nor stopped, false otherwise
   */
  protected def isRunning: Boolean = !isPaused && !isStopped

  /**
   * Gets the display label for this time manager type.
   * 
   * Used in logging and debugging to identify the specific time manager implementation.
   * Must be implemented by concrete subclasses.
   * 
   * @return A string label identifying this time manager type
   */
  protected def getLabel: String

  // ==================== ABSTRACT LIFECYCLE HOOKS ====================

  /**
   * Called when the simulation starts.
   * 
   * This hook allows concrete implementations to perform specific initialization
   * logic when the simulation begins execution.
   * 
   * @param start The start simulation event containing initial parameters
   */
  protected def onSimulationStart(start: StartSimulationTimeEvent): Unit

  /**
   * Called when the simulation is paused.
   * 
   * Optional hook for implementations to perform cleanup or state saving
   * when the simulation is paused. Default implementation does nothing.
   */
  protected def onSimulationPause(): Unit = {}

  /**
   * Called when the simulation is resumed.
   * 
   * Optional hook for implementations to restore state or restart processes
   * when the simulation resumes from a paused state. Default implementation does nothing.
   */
  protected def onSimulationResume(): Unit = {}

  /**
   * Called when the simulation stops.
   * 
   * Optional hook for implementations to perform cleanup when the simulation
   * is permanently stopped. Default implementation does nothing.
   */
  protected def onSimulationStop(): Unit = {}

  /**
   * Processes spontaneous simulation events.
   * 
   * This abstract method must be implemented by concrete time managers to handle
   * spontaneous events according to their specific time management strategy.
   * 
   * @param spontaneous The spontaneous event to process
   */
  protected def actSpontaneous(spontaneous: SpontaneousEvent): Unit

  // ==================== UTILITY METHODS ====================

  /**
   * Sends an event to an actor in a shard.
   * 
   * Routes events to actors managed by the sharding system using the class type
   * to determine the appropriate shard and actor ID for targeting.
   * 
   * @param classType The class type of the target actor
   * @param actorId The unique identifier of the target actor
   * @param event The event to send to the actor
   */
  protected def sendToShard(classType: String, actorId: String, event: Any): Unit = {
    getShardRef(getModelClassName(classType)) ! EntityEnvelopeEvent(
      actorId,
      event.asInstanceOf[AnyRef]
    )
  }

  /**
   * Sends an event to an actor in a pool.
   * 
   * Routes events to actors managed by the pooling system using the actor ID
   * to determine the appropriate pool and target actor.
   * 
   * @param actorId The unique identifier of the target actor
   * @param event The event to send to the actor
   */
  protected def sendToPool(actorId: String, event: Any): Unit = {
    getActorPoolRef(actorId) ! event.asInstanceOf[AnyRef]
  }
}
