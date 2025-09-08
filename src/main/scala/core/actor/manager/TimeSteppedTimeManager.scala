package org.interscity.htc
package core.actor.manager

import core.actor.manager.base.LocalTimeManager
import core.entity.event.SpontaneousEvent
import core.entity.event.control.simulation.{AdvanceToTick, TickCompleted, BarrierSynchronization}
import core.types.Tick

import org.apache.pekko.actor.{ActorRef, Props}
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.{RegisterActorEvent, StartSimulationTimeEvent}
import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum

import scala.collection.mutable

/**
 * Time-Stepped Simulation Time Manager implementation.
 * 
 * This Local Time Manager implements the Time-Stepped simulation paradigm,
 * providing synchronized time advancement where all actors proceed in lockstep
 * through discrete time intervals (steps).
 * 
 * Key characteristics of Time-Stepped simulation:
 * - Fixed time step intervals for synchronous advancement
 * - Barrier synchronization ensuring all actors complete each step
 * - Deterministic execution order within each time step
 * - Optimal for tightly coupled simulations requiring synchronization
 * - Uses TickBarrier coordination for step completion detection
 * 
 * The Time-Stepped approach ensures that all actors complete their processing
 * for the current time step before any actor can advance to the next step,
 * providing strong consistency guarantees.
 * 
 * @param simulationDuration Maximum duration of the simulation in ticks
 * @param simulationManager Reference to the main simulation manager
 * @param parentManager Reference to the Global Time Manager for coordination
 * @param stepSize The size of each time step in simulation ticks (default: 1)
 * 
 * @author Hyperbolic Time Chamber Team
 * @version 1.4.0
 * @since 1.0.0
 */
class TimeSteppedTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  parentManager: ActorRef,
  val stepSize: Tick = 1
) extends LocalTimeManager(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      parentManager = parentManager,
      timeManagerType = LocalTimeManagerTypeEnum.TimeStepped
    ) {

  // Estado específico do Time-Stepped
  private val actorStepStatus = mutable.Map[String, Tick]() // último tick processado por cada ator
  private val tickBarriers = mutable.Map[Tick, TickBarrier]() // barreiras de sincronização
  private var currentStep: Tick = 0

  case class TickBarrier(
    targetTick: Tick,
    expectedActors: Int,
    completedActors: mutable.Set[String] = mutable.Set(),
    var isCompleted: Boolean = false
  )

  override protected def onSimulationStart(start: StartSimulationTimeEvent): Unit = {
    currentStep = start.startTick
    logInfo(s"Time-Stepped Time Manager iniciado no tempo ${start.startTick} (stepSize: $stepSize)")
  }

  /**
   * Time-Stepped não aceita scheduling individual de eventos
   */
  override protected def acceptsScheduling: Boolean = false

  /**
   * Cria comando AdvanceToTick para ator
   */
  override protected def createEventForActor(tick: Tick, identity: Identify): Any = {
    AdvanceToTick(targetTick = tick)
  }

  /**
   * Handle para eventos específicos do Time-Stepped
   */
  override protected def handleSpecificEvent(event: Any): Unit = event match {
    case advance: AdvanceToTick => handleAdvanceToTick(advance)
    case completed: TickCompleted => handleTickCompleted(completed)
    case barrier: BarrierSynchronization => handleBarrierSync(barrier)
    case _ => logDebug(s"Time-Stepped: Evento específico não tratado: ${event.getClass.getSimpleName}")
  }

  override protected def onActorRegistered(event: RegisterActorEvent): Unit = {
    actorStepStatus.put(event.actorId, currentStep)
    logInfo(s"Time-Stepped: Ator ${event.actorId} registrado (Total: ${registeredActors.size})")
    
    // Se for o primeiro ator, iniciar primeiro passo
    if (registeredActors.size == 1) {
      scheduleNextStep()
    }
  }

  /**
   * Processa comando AdvanceToTick
   */
  private def handleAdvanceToTick(advance: AdvanceToTick): Unit = {
    logDebug(s"Time-Stepped: Processando AdvanceToTick para tick ${advance.targetTick}")
    
    if (registeredActors.nonEmpty) {
      setupTickBarrier(advance.targetTick)
      broadcastAdvanceToAllActors(advance.targetTick)
    }
  }

  /**
   * Processa confirmação TickCompleted
   */
  private def handleTickCompleted(completed: TickCompleted): Unit = {
    logDebug(s"Time-Stepped: Ator ${completed.actorId} completou tick ${completed.completedTick}")
    
    // Atualizar status do ator
    actorStepStatus.put(completed.actorId, completed.completedTick)
    
    // Marcar na barreira
    tickBarriers.get(completed.completedTick) match {
      case Some(barrier) =>
        barrier.completedActors.add(completed.actorId)
        
        // Verificar se todos completaram
        if (barrier.completedActors.size >= barrier.expectedActors) {
          completeTickBarrier(completed.completedTick)
        }
        
      case None =>
        logWarn(s"TickCompleted recebido para tick sem barreira: ${completed.completedTick}")
    }
  }

  /**
   * Handle para sincronização de barreira
   */
  private def handleBarrierSync(barrier: BarrierSynchronization): Unit = {
    logDebug(s"Time-Stepped: Barreira de sincronização para tick ${barrier.tick}")
    setupTickBarrier(barrier.tick, barrier.expectedActors)
  }

  /**
   * Configura barreira de sincronização
   */
  private def setupTickBarrier(tick: Tick, expectedActors: Int = registeredActors.size): Unit = {
    val barrier = TickBarrier(
      targetTick = tick,
      expectedActors = expectedActors
    )
    tickBarriers.put(tick, barrier)
    logDebug(s"Barreira configurada para tick $tick com $expectedActors atores")
  }

  /**
   * Broadcast AdvanceToTick para todos os atores
   */
  private def broadcastAdvanceToAllActors(targetTick: Tick): Unit = {
    logInfo(s"Time-Stepped: Broadcasting AdvanceToTick($targetTick) para ${registeredActors.size} atores")
    
    registeredActors.foreach { actorId =>
      // Encontrar identidade do ator (simplificado - em implementação real seria um lookup)
      val dummyIdentity = Identify(
        id = actorId,
        resourceId = "time-stepped-resource",
        classType = "TimeSteppedActor",
        actorRef = s"actor://$actorId",
        actorType = "PoolDistributed"
      )
      
      sendEventToActor(targetTick, dummyIdentity)
    }
  }

  /**
   * Completa barreira de sincronização
   */
  private def completeTickBarrier(tick: Tick): Unit = {
    tickBarriers.get(tick).foreach { barrier =>
      barrier.isCompleted = true
      logInfo(s"Time-Stepped: Barreira completa para tick $tick - todos os atores sincronizados")
      
      // Atualizar tempo local
      localTickOffset = tick
      currentStep = tick
      
      // Limpar barreira antiga
      tickBarriers.remove(tick)
      
      // Reportar para Global Time Manager
      reportToGlobalTimeManager()
      
      // Agendar próximo passo se necessário
      if (tick < simulationDuration) {
        scheduleNextStep()
      }
    }
  }

  /**
   * Agenda próximo passo
   */
  private def scheduleNextStep(): Unit = {
    val nextStep = currentStep + stepSize
    if (nextStep <= simulationDuration) {
      logDebug(s"Time-Stepped: Agendando próximo passo: $nextStep")
      
      // Usar scheduler para dar tempo aos atores processarem
      import scala.concurrent.duration._
      context.system.scheduler.scheduleOnce(
        10.milliseconds,
        self,
        AdvanceToTick(nextStep)
      )(context.dispatcher)
    } else {
      logInfo("Time-Stepped: Simulação finalizada - sem mais passos")
    }
  }

  override protected def onSpontaneousEvent(spontaneous: SpontaneousEvent): Unit = {
    // Time-Stepped pode usar eventos espontâneos para coordenação interna
    logDebug(s"Time-Stepped: Evento espontâneo no tick ${spontaneous.tick}")
  }

  override protected def onSimulationStop(): Unit = {
    val duration = System.currentTimeMillis() - startTime
    logInfo(s"Time-Stepped simulação finalizada:")
    logInfo(s"  Tempo final: $localTickOffset")
    logInfo(s"  Duração: ${duration}ms")
    logInfo(s"  Step size: $stepSize")
    logInfo(s"  Passos processados: ${localTickOffset / stepSize}")
    logInfo(s"  Atores registrados: ${registeredActors.size}")
    logInfo(s"  Barreiras pendentes: ${tickBarriers.size}")
  }

  /**
   * Remove ator do sistema Time-Stepped
   */
  def unregisterActor(actorId: String): Unit = {
    registeredActors.remove(actorId)
    actorStepStatus.remove(actorId)
    logInfo(s"Time-Stepped: Ator $actorId removido do sistema")
  }

  /**
   * Obtém estatísticas do Time-Stepped
   */
  def getTimeSteppedStats: Map[String, Any] = {
    Map(
      "currentStep" -> currentStep,
      "stepSize" -> stepSize,
      "registeredActors" -> registeredActors.size,
      "pendingBarriers" -> tickBarriers.size,
      "actorStepStatus" -> actorStepStatus.toMap
    )
  }
}

object TimeSteppedTimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    parentManager: ActorRef,
    stepSize: Tick = 1
  ): Props =
    Props(classOf[TimeSteppedTimeManager], simulationDuration, simulationManager, parentManager, stepSize)
}
