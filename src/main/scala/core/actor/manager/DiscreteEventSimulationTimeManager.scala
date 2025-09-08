package org.interscity.htc
package core.actor.manager

import core.actor.manager.base.LocalTimeManager
import core.entity.event.SpontaneousEvent
import core.types.Tick

import org.apache.pekko.actor.{ActorRef, Props}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.{RegisterActorEvent, StartSimulationTimeEvent}
import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum

/**
 * Discrete Event Simulation Time Manager implementation.
 * 
 * This Local Time Manager implements the Discrete Event Simulation (DES) paradigm,
 * providing event-driven time management where simulation time advances based on
 * the occurrence of discrete events rather than fixed time steps.
 * 
 * Key characteristics of DES time management:
 * - Event-driven time advancement (no fixed time steps)
 * - Events processed in chronological order
 * - Simulation time jumps between event timestamps
 * - Optimal for sparse event distributions
 * - Maintains 100% compatibility with existing DES components
 * 
 * This implementation preserves the original DES behavior while integrating
 * with the new object-oriented time management architecture. It accepts
 * event scheduling and processes events according to their timestamps.
 * 
 * @param simulationDuration Maximum duration of the simulation in ticks
 * @param simulationManager Reference to the main simulation manager
 * @param parentManager Reference to the Global Time Manager for coordination
 * 
 * @author Hyperbolic Time Chamber Team
 * @version 1.4.0
 * @since 1.0.0
 */
class DiscreteEventSimulationTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: ActorRef
) extends LocalTimeManager(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      parentManager = parentManager,
      timeManagerType = LocalTimeManagerTypeEnum.DiscreteEventSimulation
    ) {

  /**
   * Called when the DES simulation starts.
   * 
   * Logs the initialization of the DES Time Manager with the starting tick.
   * 
   * @param start The start simulation event containing initial parameters
   */
  override protected def onSimulationStart(start: StartSimulationTimeEvent): Unit = {
    logInfo(s"DES Time Manager iniciado no tempo ${start.startTick}")
  }

  /**
   * Indicates that DES accepts event scheduling (original DES behavior).
   * 
   * DES is event-driven and relies on scheduling events at specific
   * simulation times for proper operation.
   * 
   * @return true, as DES always accepts event scheduling
   */
  override protected def acceptsScheduling: Boolean = true

  /**
   * Creates a spontaneous event for an actor (original DES behavior).
   * 
   * In DES, actors are triggered by spontaneous events that carry
   * the simulation time and manager reference.
   * 
   * @param tick The simulation tick for the event
   * @param identity The identity information of the target actor
   * @return A spontaneous event configured for DES operation
   */
  override protected def createEventForActor(tick: Tick, identity: Identify): Any = {
    SpontaneousEvent(
      tick = tick,
      actorRef = self
    )
  }

  /**
   * DES usa o comportamento padrão para todos os eventos
   */
  override protected def handleSpecificEvent(event: Any): Unit = {
    logDebug(s"DES: Evento não tratado especificamente: ${event.getClass.getSimpleName}")
  }

  override protected def onActorRegistered(event: RegisterActorEvent): Unit = {
    logDebug(s"DES: Ator ${event.actorId} registrado")
  }

  override protected def onSpontaneousEvent(spontaneous: SpontaneousEvent): Unit = {
    logDebug(s"DES: Evento espontâneo no tick ${spontaneous.tick}")
  }

  override protected def onSimulationStop(): Unit = {
    val duration = System.currentTimeMillis() - startTime
    logInfo(s"DES simulação finalizada:")
    logInfo(s"  Tempo final: $localTickOffset")
    logInfo(s"  Duração: ${duration}ms")
    logInfo(s"  Eventos processados: $countScheduled")
    logInfo(s"  Atores registrados: ${registeredActors.size}")
  }
}

/**
 * Companion object for DiscreteEventSimulationTimeManager containing factory methods.
 */
object DiscreteEventSimulationTimeManager {
  
  /**
   * Creates Props for DiscreteEventSimulationTimeManager actor instantiation.
   * 
   * @param simulationDuration Maximum duration of the simulation in ticks
   * @param simulationManager Reference to the main simulation manager
   * @param parentManager Reference to the Global Time Manager for coordination
   * @return Props object for creating DES Time Manager actors
   */
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    parentManager: ActorRef
  ): Props =
    Props(classOf[DiscreteEventSimulationTimeManager], simulationDuration, simulationManager, parentManager)
}
