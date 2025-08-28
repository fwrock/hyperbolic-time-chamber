package org.interscity.htc
package core.actor.manager.time

import core.entity.event.{ EntityEnvelopeEvent, FinishEvent, SpontaneousEvent }
import core.types.Tick
import core.actor.manager.time.protocol._
import core.entity.control.ScheduledActors

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent
import org.htc.protobuf.core.entity.event.control.execution.StartSimulationTimeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.util.StringUtil

import scala.collection.mutable

/**
 * DiscreteEventSimulationTimeManager - Gerenciador de tempo para simulação de eventos discretos.
 * 
 * Esta é uma refatoração do TimeManager original para trabalhar como LocalTimeManager
 * na arquitetura multi-paradigma, mantendo compatibilidade completa com o sistema existente.
 */
class DiscreteEventSimulationTimeManager(
  override val globalTimeManager: ActorRef,
  override val simulationDuration: Tick,
  override val simulationManager: ActorRef
) extends LocalTimeManager(
      globalTimeManager = globalTimeManager,
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      actorId = s"DiscreteEventSimulationTimeManager-${System.nanoTime()}"
    ) {

  override def ltmType: String = "DiscreteEventSimulation"

  // Estado específico do DES
  private val registeredActors = mutable.Set[String]()
  private val scheduledActors = mutable.Map[Tick, ScheduledActors]()
  private val runningEvents = mutable.Set[Identify]()
  private var startTime: Long = 0

  override def onStart(): Unit = {
    super.onStart()
    logInfo("DES_LTM iniciado - Gerenciador de Eventos Discretos")
  }

  /**
   * Receive específico para eventos DES
   */
  override protected def specificTimeManagementReceive: Receive = {
    case register: RegisterActorEvent => registerActor(register)
    case schedule: ScheduleEvent => scheduleEvent(schedule)
    case finish: FinishEvent => finishEvent(finish)
    case spontaneous: SpontaneousEvent => if (isActive) actSpontaneous(spontaneous)
  }

  // ==================== IMPLEMENTAÇÃO DOS MÉTODOS ABSTRATOS ====================

  /**
   * Verifica se há eventos agendados
   */
  override protected def hasScheduledEvents(): Boolean = {
    scheduledActors.nonEmpty || runningEvents.nonEmpty
  }

  /**
   * Verifica se pode avançar até o tempo especificado
   */
  override protected def canAdvanceToTime(targetTime: Tick): Boolean = {
    // DES pode avançar se não há eventos rodando ou se o próximo evento é <= targetTime
    if (runningEvents.nonEmpty) {
      false // Não pode avançar enquanto há eventos em execução
    } else if (scheduledActors.isEmpty) {
      true // Pode avançar se não há eventos agendados
    } else {
      val nextEventTime = scheduledActors.keys.min
      nextEventTime <= targetTime
    }
  }

  /**
   * Sugere tempo alternativo baseado no próximo evento
   */
  override protected def getAlternativeTime(proposedTime: Tick): Option[Tick] = {
    if (runningEvents.nonEmpty) {
      Some(currentLocalTime) // Não pode avançar ainda
    } else if (scheduledActors.nonEmpty) {
      Some(scheduledActors.keys.min) // Próximo evento agendado
    } else {
      None // Sem alternativa
    }
  }

  /**
   * Executa avanço de tempo específico do DES
   */
  override protected def advanceToTime(targetTime: Tick): Unit = {
    logDebug(s"DES avançando tempo de $currentLocalTime para $targetTime")
    
    // Processar todos os eventos até o tempo alvo
    while (scheduledActors.nonEmpty && scheduledActors.keys.min <= targetTime && runningEvents.isEmpty) {
      val nextEventTime = scheduledActors.keys.min
      processEventsAtTime(nextEventTime)
    }
  }

  /**
   * Retorna número de eventos pendentes
   */
  override protected def getQueueSize(): Int = {
    scheduledActors.values.map(_.actorsRef.size).sum + runningEvents.size
  }

  // ==================== LÓGICA ESPECÍFICA DO DES ====================

  /**
   * Registra um ator no DES
   */
  private def registerActor(event: RegisterActorEvent): Unit = {
    registeredActors.add(event.actorId)
    logDebug(s"Ator registrado no DES: ${event.actorId}")
    
    // Agendar evento inicial se necessário
    if (event.identify.isDefined) {
      scheduleEvent(
        ScheduleEvent(
          tick = event.startTick,
          actorRef = event.actorId,
          identify = event.identify
        )
      )
    }
  }

  /**
   * Agenda um evento no DES
   */
  private def scheduleEvent(schedule: ScheduleEvent): Unit = {
    logDebug(s"Agendando evento DES no tempo ${schedule.tick} para ator ${schedule.actorRef}")
    
    scheduledActors.get(schedule.tick) match {
      case Some(scheduled) =>
        schedule.identify.foreach(scheduled.actorsRef.add)
      case None =>
        scheduledActors.put(
          schedule.tick,
          ScheduledActors(
            tick = schedule.tick,
            actorsRef = mutable.Set(schedule.identify.get)
          )
        )
    }
  }

  /**
   * Processa eventos em um tempo específico
   */
  private def processEventsAtTime(eventTime: Tick): Unit = {
    scheduledActors.get(eventTime) match {
      case Some(scheduled) =>
        logDebug(s"Processando ${scheduled.actorsRef.size} eventos no tempo $eventTime")
        
        // Mover atores para execução
        scheduled.actorsRef.foreach { actor =>
          runningEvents.add(actor)
        }
        
        // Enviar eventos espontâneos
        sendSpontaneousEvents(eventTime, scheduled.actorsRef)
        
        // Remover da agenda
        scheduledActors.remove(eventTime)
        
      case None =>
        logWarn(s"Tentativa de processar eventos inexistentes no tempo $eventTime")
    }
  }

  /**
   * Envia eventos espontâneos para os atores
   */
  private def sendSpontaneousEvents(tick: Tick, actorsRef: mutable.Set[Identify]): Unit = {
    logDebug(s"Enviando eventos espontâneos no tempo $tick para ${actorsRef.size} atores")
    
    actorsRef.foreach { actor =>
      sendSpontaneousEvent(tick, actor)
    }
  }

  /**
   * Envia evento espontâneo para um ator específico
   */
  private def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    if (identity.actorType == CreationTypeEnum.PoolDistributed.toString) {
      sendSpontaneousEventPool(tick, identity)
    } else {
      sendSpontaneousEventShard(tick, identity)
    }
  }

  /**
   * Envia evento espontâneo para ator em pool
   */
  private def sendSpontaneousEventPool(tick: Tick, identity: Identify): Unit = {
    getActorPoolRef(identity.id) ! SpontaneousEvent(
      tick = tick,
      actorRef = self
    )
  }

  /**
   * Envia evento espontâneo para ator em shard
   */
  private def sendSpontaneousEventShard(tick: Tick, identity: Identify): Unit = {
    getShardRef(StringUtil.getModelClassName(identity.classType)) ! EntityEnvelopeEvent(
      identity.id,
      SpontaneousEvent(
        tick = tick,
        actorRef = self
      )
    )
  }

  /**
   * Trata finalização de evento
   */
  private def finishEvent(finish: FinishEvent): Unit = {
    if (finish.timeManager == self) {
      logDebug(s"Finalizando evento do ator ${finish.identify.id}")
      
      // Remover ator da execução
      runningEvents.filterInPlace(_.id != finish.identify.id)
      
      // Agendar próximo evento se necessário
      finish.scheduleTick.map(_.toLong).foreach { nextTick =>
        scheduleEvent(
          ScheduleEvent(
            tick = nextTick,
            actorRef = finish.identify.id,
            identify = Some(finish.identify)
          )
        )
      }
      
      // Verificar se ator deve ser destruído
      if (finish.destruct) {
        registeredActors.remove(finish.identify.id)
        logDebug(s"Ator ${finish.identify.id} removido do registro DES")
      }
    } else {
      // Reencaminhar para o timeManager correto
      finish.timeManager ! finish
    }
  }

  override def actSpontaneous(spontaneous: SpontaneousEvent): Unit = {
    // Em DES, eventos espontâneos são principalmente para coordenação interna
    logDebug(s"Evento espontâneo recebido no DES no tempo ${spontaneous.tick}")
  }

  // ==================== HOOKS ESPECÍFICOS ====================

  override protected def onSimulationStart(start: StartSimulationTimeEvent): Unit = {
    startTime = System.currentTimeMillis()
    logInfo(s"DES simulação iniciada no tempo ${start.startTick}")
  }

  override protected def onSimulationStop(): Unit = {
    val duration = System.currentTimeMillis() - startTime
    logInfo(s"DES simulação finalizada:")
    logInfo(s"  Tempo final: $currentLocalTime")
    logInfo(s"  Duração: ${duration}ms")
    logInfo(s"  Atores registrados: ${registeredActors.size}")
    logInfo(s"  Eventos pendentes: ${getQueueSize()}")
  }

  /**
   * Obtém estatísticas do DES para debug
   */
  def getStatistics: String = {
    s"""DES_LTM Statistics:
       |  Current Time: $currentLocalTime
       |  Registered Actors: ${registeredActors.size}
       |  Scheduled Events: ${scheduledActors.size}
       |  Running Events: ${runningEvents.size}
       |  Queue Size: ${getQueueSize()}
       |  Is Active: $isActive""".stripMargin
  }
}

object DiscreteEventSimulationTimeManager {
  def props(
    globalTimeManager: ActorRef,
    simulationDuration: Tick,
    simulationManager: ActorRef
  ): Props =
    Props(classOf[DiscreteEventSimulationTimeManager], globalTimeManager, simulationDuration, simulationManager)
}
