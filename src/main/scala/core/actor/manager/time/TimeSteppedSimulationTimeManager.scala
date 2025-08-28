package org.interscity.htc
package core.actor.manager.time

import core.entity.event.{ EntityEnvelopeEvent, SpontaneousEvent }
import core.types.Tick
import core.actor.manager.time.protocol._

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.control.execution.{ RegisterActorEvent, StartSimulationTimeEvent }
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.util.StringUtil

import scala.collection.mutable

/**
 * TimeSteppedSimulationTimeManager - Gerenciador de tempo para simulação sincronizada por passos.
 * 
 * Coordena atores que avançam em passos de tempo fixos e sincronizados,
 * ideal para simulações de mobilidade e sistemas que requerem coordenação temporal.
 */
class TimeSteppedSimulationTimeManager(
  override val globalTimeManager: ActorRef,
  override val simulationDuration: Tick,
  override val simulationManager: ActorRef,
  val stepSize: Tick = 1
) extends LocalTimeManager(
      globalTimeManager = globalTimeManager,
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      actorId = s"TimeSteppedSimulationTimeManager-${System.nanoTime()}"
    ) {

  override def ltmType: String = "TimeSteppedSimulation"

  // Estado específico do Time-Stepped
  private val registeredActors = mutable.Map[String, TimeSteppedActorInfo]()
  private val tickBarrier = mutable.Map[Tick, TickBarrierInfo]()
  private var currentProcessingTick: Option[Tick] = None
  private var startTime: Long = 0

  case class TimeSteppedActorInfo(
    actorId: String,
    identify: Identify,
    isActive: Boolean = true,
    lastProcessedTick: Tick = 0
  )

  case class TickBarrierInfo(
    targetTick: Tick,
    expectedActors: Int,
    completedActors: mutable.Set[String] = mutable.Set(),
    var isCompleted: Boolean = false
  )

  override def onStart(): Unit = {
    super.onStart()
    logInfo(s"TimeStepped_LTM iniciado - Gerenciador de Passos de Tempo (stepSize: $stepSize)")
  }

  /**
   * Receive específico para eventos Time-Stepped
   */
  override protected def specificTimeManagementReceive: Receive = {
    case register: RegisterActorEvent => registerActor(register)
    case completed: TickCompleted => handleTickCompleted(completed)
    case spontaneous: SpontaneousEvent => if (isActive) actSpontaneous(spontaneous)
  }

  // ==================== IMPLEMENTAÇÃO DOS MÉTODOS ABSTRATOS ====================

  /**
   * Verifica se há eventos agendados (sempre true enquanto há atores ativos)
   */
  override protected def hasScheduledEvents(): Boolean = {
    registeredActors.values.exists(_.isActive) || currentProcessingTick.isDefined
  }

  /**
   * Verifica se pode avançar até o tempo especificado
   */
  override protected def canAdvanceToTime(targetTime: Tick): Boolean = {
    currentProcessingTick match {
      case Some(processingTick) =>
        // Se estamos processando um tick, só pode avançar se todos os atores completaram
        tickBarrier.get(processingTick).exists(_.isCompleted)
      case None =>
        // Se não estamos processando, pode avançar
        true
    }
  }

  /**
   * Sugere tempo alternativo se não pode avançar
   */
  override protected def getAlternativeTime(proposedTime: Tick): Option[Tick] = {
    currentProcessingTick match {
      case Some(processingTick) =>
        // Se ainda processando, sugerir o tick atual
        Some(processingTick)
      case None =>
        // Se livre, sugerir próximo passo
        Some(alignToStep(proposedTime))
    }
  }

  /**
   * Executa avanço de tempo específico do Time-Stepped
   */
  override protected def advanceToTime(targetTime: Tick): Unit = {
    logDebug(s"TimeStepped avançando de $currentLocalTime para $targetTime")
    
    // Processar passos de tempo até o alvo
    var nextTick = alignToStep(currentLocalTime + stepSize)
    
    while (nextTick <= targetTime && registeredActors.values.exists(_.isActive)) {
      processTimeStep(nextTick)
      nextTick += stepSize
    }
  }

  /**
   * Retorna número de atores ativos
   */
  override protected def getQueueSize(): Int = {
    registeredActors.values.count(_.isActive)
  }

  // ==================== LÓGICA ESPECÍFICA DO TIME-STEPPED ====================

  /**
   * Registra um ator no sistema Time-Stepped
   */
  private def registerActor(event: RegisterActorEvent): Unit = {
    if (event.identify.isDefined) {
      val actorInfo = TimeSteppedActorInfo(
        actorId = event.actorId,
        identify = event.identify.get,
        isActive = true,
        lastProcessedTick = currentLocalTime
      )
      
      registeredActors.put(event.actorId, actorInfo)
      logInfo(s"Ator registrado no TimeStepped: ${event.actorId} (Total: ${registeredActors.size})")
    }
  }

  /**
   * Processa um passo de tempo
   */
  private def processTimeStep(tick: Tick): Unit = {
    val activeActors = registeredActors.values.filter(_.isActive).toSeq
    
    if (activeActors.isEmpty) {
      logDebug(s"Nenhum ator ativo para processar no tick $tick")
      return
    }
    
    logInfo(s"Processando passo de tempo $tick com ${activeActors.size} atores")
    
    // Configurar barreira de sincronização
    currentProcessingTick = Some(tick)
    val barrier = TickBarrierInfo(
      targetTick = tick,
      expectedActors = activeActors.size
    )
    tickBarrier.put(tick, barrier)
    
    // Broadcast AdvanceToTick para todos os atores ativos
    broadcastAdvanceToTick(tick, activeActors)
  }

  /**
   * Envia comando AdvanceToTick para todos os atores
   */
  private def broadcastAdvanceToTick(tick: Tick, actors: Seq[TimeSteppedActorInfo]): Unit = {
    logDebug(s"Broadcasting AdvanceToTick($tick) para ${actors.size} atores")
    
    actors.foreach { actorInfo =>
      sendAdvanceToTickEvent(tick, actorInfo.identify)
    }
  }

  /**
   * Envia AdvanceToTick para um ator específico
   */
  private def sendAdvanceToTickEvent(tick: Tick, identity: Identify): Unit = {
    val advanceEvent = AdvanceToTick(tick)
    
    if (identity.actorType == CreationTypeEnum.PoolDistributed.toString) {
      getActorPoolRef(identity.id) ! advanceEvent
    } else {
      getShardRef(StringUtil.getModelClassName(identity.classType)) ! EntityEnvelopeEvent(
        identity.id,
        advanceEvent
      )
    }
  }

  /**
   * Trata completamento de tick por um ator
   */
  private def handleTickCompleted(completed: TickCompleted): Unit = {
    logDebug(s"Ator ${completed.actorId} completou tick ${completed.completedTick}")
    
    tickBarrier.get(completed.completedTick) match {
      case Some(barrier) =>
        barrier.completedActors.add(completed.actorId)
        
        // Atualizar último tick processado pelo ator
        registeredActors.get(completed.actorId).foreach { actorInfo =>
          registeredActors.update(
            completed.actorId,
            actorInfo.copy(lastProcessedTick = completed.completedTick)
          )
        }
        
        // Verificar se todos os atores completaram
        if (barrier.completedActors.size >= barrier.expectedActors) {
          completeTickBarrier(completed.completedTick)
        }
        
      case None =>
        logWarn(s"Recebido TickCompleted para tick inexistente: ${completed.completedTick}")
    }
  }

  /**
   * Completa a barreira de sincronização para um tick
   */
  private def completeTickBarrier(tick: Tick): Unit = {
    tickBarrier.get(tick).foreach { barrier =>
      barrier.isCompleted = true
      logDebug(s"Barreira de sincronização completa para tick $tick")
      
      // Limpar estado do tick atual se for este
      if (currentProcessingTick.contains(tick)) {
        currentProcessingTick = None
        
        // Limpar barreiras antigas para economizar memória
        cleanupOldBarriers(tick)
      }
    }
  }

  /**
   * Remove barreiras antigas para economizar memória
   */
  private def cleanupOldBarriers(currentTick: Tick): Unit = {
    val oldTicks = tickBarrier.keys.filter(_ < currentTick - 10) // Manter apenas últimos 10 ticks
    oldTicks.foreach(tickBarrier.remove)
    
    if (oldTicks.nonEmpty) {
      logDebug(s"Removidas ${oldTicks.size} barreiras antigas")
    }
  }

  /**
   * Alinha tempo ao próximo passo válido
   */
  private def alignToStep(time: Tick): Tick = {
    ((time + stepSize - 1) / stepSize) * stepSize
  }

  override def actSpontaneous(spontaneous: SpontaneousEvent): Unit = {
    // Time-stepped pode usar eventos espontâneos para coordenação interna
    logDebug(s"Evento espontâneo recebido no TimeStepped no tempo ${spontaneous.tick}")
  }

  // ==================== HOOKS ESPECÍFICOS ====================

  override protected def onSimulationStart(start: StartSimulationTimeEvent): Unit = {
    startTime = System.currentTimeMillis()
    logInfo(s"TimeStepped simulação iniciada no tempo ${start.startTick}")
  }

  override protected def onSimulationPause(): Unit = {
    logInfo("TimeStepped simulação pausada")
    // Pode enviar evento de pausa para atores se necessário
  }

  override protected def onSimulationResume(): Unit = {
    logInfo("TimeStepped simulação retomada")
    // Pode enviar evento de retomada para atores se necessário
  }

  override protected def onSimulationStop(): Unit = {
    val duration = System.currentTimeMillis() - startTime
    logInfo(s"TimeStepped simulação finalizada:")
    logInfo(s"  Tempo final: $currentLocalTime")
    logInfo(s"  Duração: ${duration}ms")
    logInfo(s"  Atores registrados: ${registeredActors.size}")
    logInfo(s"  Step size: $stepSize")
    logInfo(s"  Passos processados: ${currentLocalTime / stepSize}")
  }

  // ==================== UTILITÁRIOS PÚBLICOS ====================

  /**
   * Remove um ator do registro (quando ele termina sua execução)
   */
  def unregisterActor(actorId: String): Unit = {
    registeredActors.get(actorId).foreach { actorInfo =>
      registeredActors.update(actorId, actorInfo.copy(isActive = false))
      logDebug(s"Ator $actorId marcado como inativo")
    }
  }

  /**
   * Obtém estatísticas do TimeStepped para debug
   */
  def getStatistics: String = {
    val activeActors = registeredActors.values.count(_.isActive)
    val inactiveActors = registeredActors.size - activeActors
    
    s"""TimeStepped_LTM Statistics:
       |  Current Time: $currentLocalTime
       |  Step Size: $stepSize
       |  Active Actors: $activeActors
       |  Inactive Actors: $inactiveActors
       |  Current Processing Tick: ${currentProcessingTick.getOrElse("None")}
       |  Active Barriers: ${tickBarrier.size}
       |  Is Active: $isActive""".stripMargin
  }
}

object TimeSteppedSimulationTimeManager {
  def props(
    globalTimeManager: ActorRef,
    simulationDuration: Tick,
    simulationManager: ActorRef,
    stepSize: Tick = 1
  ): Props =
    Props(classOf[TimeSteppedSimulationTimeManager], globalTimeManager, simulationDuration, simulationManager, stepSize)
}
