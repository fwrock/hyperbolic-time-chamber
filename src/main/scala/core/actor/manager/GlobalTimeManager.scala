package org.interscity.htc
package core.actor.manager

import core.entity.event.{ EntityEnvelopeEvent, FinishEvent, SpontaneousEvent }
import core.types.Tick
import core.actor.manager.time.protocol._
import core.actor.manager.time.LocalTimeManager

import org.apache.pekko.actor.{ ActorRef, Props }
import core.entity.state.DefaultState

import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent, StopSimulationEvent }
import org.interscity.htc.core.util.ManagerConstantsUtil.GLOBAL_TIME_MANAGER_ACTOR_NAME

import scala.collection.mutable

/**
 * GlobalTimeManager (GTM) - Coordenador central do tempo global na simulação multi-paradigma.
 * 
 * Responsabilidades:
 * - Manter uma lista de LocalTimeManagers (LTMs) registrados
 * - Implementar o protocolo de sincronização de 4 fases
 * - Ser a autoridade sobre o tempo global (LBTS - Lower Bound Time Stamp)
 * - Coordenar o avanço de tempo entre diferentes paradigmas
 */
class GlobalTimeManager(
  val simulationDuration: Tick,
  val simulationManager: ActorRef
) extends BaseManager[DefaultState](
      timeManager = null,
      actorId = GLOBAL_TIME_MANAGER_ACTOR_NAME
    ) {

  // Estado interno do GlobalTimeManager
  private var globalTime: Tick = 0
  private var lowerBoundTimeStamp: Tick = 0  // Lower Bound Time Stamp
  private var isSimulationRunning: Boolean = false
  
  // Registro de LocalTimeManagers
  private val localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerInfo] = mutable.Map()
  
  // Protocolo de sincronização de 4 fases
  private var synchronizationPhase: SynchronizationPhase = SynchronizationPhase.Idle
  private val phaseResponses: mutable.Set[ActorRef] = mutable.Set()

  case class LocalTimeManagerInfo(
    managerType: String,  // "DiscreteEventSimulation", "TimeSteppedSimulation", "OptimisticSimulation"
    currentTime: Tick,
    proposedTime: Option[Tick] = None,
    isReady: Boolean = false
  )

  sealed trait SynchronizationPhase
  object SynchronizationPhase {
    case object Idle extends SynchronizationPhase
    case object RequestTime extends SynchronizationPhase
    case object ProposeTime extends SynchronizationPhase
    case object GrantTime extends SynchronizationPhase
    case object Acknowledge extends SynchronizationPhase
  }

  override def onStart(): Unit = {
    logInfo("GlobalTimeManager iniciado - Aguardando registros de LocalTimeManagers")
  }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterLTMEvent => registerLocalTimeManager(register)
    
    // Protocolo de sincronização de 4 fases
    case request: TimeRequestEvent => handleTimeRequest(request)
    case propose: TimeProposeEvent => handleTimePropose(propose)
    case ack: TimeAcknowledgeEvent => handleTimeAcknowledge(ack)
    
    case StopSimulationEvent => stopSimulation()
    case _ => logWarn("Evento não tratado no GTM")
  }

  /**
   * Registra um novo LocalTimeManager no GlobalTimeManager
   */
  private def registerLocalTimeManager(register: RegisterLTMEvent): Unit = {
    val managerInfo = LocalTimeManagerInfo(
      managerType = register.ltmType,
      currentTime = globalTime
    )
    
    localTimeManagers.put(sender(), managerInfo)
    logInfo(s"LocalTimeManager registrado: ${register.ltmType} - Total: ${localTimeManagers.size}")
    
    // Responder com confirmação de registro
    sender() ! LTMRegistrationConfirmEvent(globalTime)
  }

  /**
   * Inicia a simulação e o protocolo de sincronização
   */
  private def startSimulation(start: StartSimulationTimeEvent): Unit = {
    globalTime = start.startTick
    lowerBoundTimeStamp = start.startTick
    isSimulationRunning = true
    
    logInfo(s"Simulação iniciada no tempo global: $globalTime")
    
    // Iniciar primeira rodada de sincronização
    if (localTimeManagers.nonEmpty) {
      startSynchronizationCycle()
    }
  }

  /**
   * Para a simulação
   */
  private def stopSimulation(): Unit = {
    isSimulationRunning = false
    
    // Notificar todos os LocalTimeManagers para parar
    localTimeManagers.keys.foreach { manager =>
      manager ! StopSimulationEvent
    }
    
    logInfo(s"Simulação finalizada no tempo global: $globalTime")
    context.stop(self)
  }

  /**
   * FASE 1: Request Time - Solicita tempo atual de todos os LocalTimeManagers
   */
  private def startSynchronizationCycle(): Unit = {
    if (!isSimulationRunning || localTimeManagers.isEmpty) return
    
    synchronizationPhase = SynchronizationPhase.RequestTime
    phaseResponses.clear()
    
    logDebug(s"Iniciando ciclo de sincronização - Fase 1: RequestTime (tempo global: $globalTime)")
    
    // Solicitar tempo atual de todos os LocalTimeManagers
    localTimeManagers.keys.foreach { manager =>
      manager ! TimeRequestEvent(globalTime)
    }
  }

  /**
   * FASE 2: Processa respostas da solicitação de tempo
   */
  private def handleTimeRequest(request: TimeRequestEvent): Unit = {
    if (synchronizationPhase != SynchronizationPhase.RequestTime) {
      logWarn(s"Recebido TimeRequestEvent fora da fase RequestTime")
      return
    }

    val timeManager = sender()
    phaseResponses.add(timeManager)
    
    // Atualizar informações do LocalTimeManager
    localTimeManagers.get(timeManager).foreach { info =>
      localTimeManagers.update(timeManager, info.copy(currentTime = globalTime))
    }

    // Se todos responderam, ir para próxima fase
    if (phaseResponses.size == localTimeManagers.size) {
      proceedToProposePhase()
    }
  }

  /**
   * FASE 3: Propose Time - Calcula LowerBoundTimeStamp e propõe novo tempo
   */
  private def proceedToProposePhase(): Unit = {
    synchronizationPhase = SynchronizationPhase.ProposeTime
    phaseResponses.clear()
    
    // Calcular Lower Bound Time Stamp
    val currentTimes = localTimeManagers.values.map(_.currentTime)
    lowerBoundTimeStamp = if (currentTimes.nonEmpty) currentTimes.min else globalTime
    
    // Determinar próximo tempo global
    val nextGlobalTime = calculateNextGlobalTime()
    
    logDebug(s"Fase 2: ProposeTime - LowerBoundTimeStamp: $lowerBoundTimeStamp, Próximo tempo: $nextGlobalTime")
    
    // Propor novo tempo para todos os LocalTimeManagers
    localTimeManagers.keys.foreach { manager =>
      manager ! TimeProposeEvent(nextGlobalTime, lowerBoundTimeStamp)
    }
  }

  /**
   * FASE 4: Processa propostas de tempo dos LocalTimeManagers
   */
  private def handleTimePropose(propose: TimeProposeEvent): Unit = {
    if (synchronizationPhase != SynchronizationPhase.ProposeTime) {
      logWarn(s"Recebido TimeProposeEvent fora da fase ProposeTime")
      return
    }

    val timeManager = sender()
    phaseResponses.add(timeManager)
    
    // Atualizar proposta do LocalTimeManager
    localTimeManagers.get(timeManager).foreach { info =>
      localTimeManagers.update(timeManager, info.copy(proposedTime = Some(propose.proposedTime)))
    }

    // Se todos responderam, ir para fase de concessão
    if (phaseResponses.size == localTimeManagers.size) {
      proceedToGrantPhase()
    }
  }

  /**
   * FASE 5: Grant Time - Concede tempo para avanço
   */
  private def proceedToGrantPhase(): Unit = {
    synchronizationPhase = SynchronizationPhase.GrantTime
    phaseResponses.clear()
    
    // Determinar tempo final baseado nas propostas
    val proposedTimes = localTimeManagers.values.flatMap(_.proposedTime)
    val grantedTime = if (proposedTimes.nonEmpty) proposedTimes.min else globalTime + 1
    
    // Verificar se simulação deve continuar
    if (grantedTime >= globalTime + simulationDuration) {
      stopSimulation()
      return
    }
    
    globalTime = grantedTime
    
    logDebug(s"Fase 3: GrantTime - Tempo concedido: $grantedTime")
    
    // Conceder tempo para todos os LocalTimeManagers
    localTimeManagers.keys.foreach { manager =>
      manager ! GrantTimeAdvanceEvent(grantedTime)
    }
  }

  /**
   * FASE 6: Processa acknowledgments dos LocalTimeManagers
   */
  private def handleTimeAcknowledge(ack: TimeAcknowledgeEvent): Unit = {
    if (synchronizationPhase != SynchronizationPhase.GrantTime) {
      logWarn(s"Recebido TimeAcknowledgeEvent fora da fase GrantTime")
      return
    }

    val timeManager = sender()
    phaseResponses.add(timeManager)
    
    logDebug(s"Acknowledgment recebido do LocalTimeManager: ${localTimeManagers.get(timeManager).map(_.managerType).getOrElse("unknown")}")

    // Se todos acknowledgaram, completar ciclo
    if (phaseResponses.size == localTimeManagers.size) {
      completeSynchronizationCycle()
    }
  }

  /**
   * Completa o ciclo de sincronização e inicia o próximo
   */
  private def completeSynchronizationCycle(): Unit = {
    synchronizationPhase = SynchronizationPhase.Idle
    
    logDebug(s"Ciclo de sincronização completo no tempo global: $globalTime")
    
    // Aguardar um pouco antes do próximo ciclo (para permitir processamento)
    import scala.concurrent.duration._
    context.system.scheduler.scheduleOnce(1.millisecond, self, "START_NEXT_CYCLE")(context.dispatcher)
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (event.tick == 0 && isSimulationRunning) {
      startSynchronizationCycle()
    } else {
      logDebug(s"Evento espontâneo não tratado: ${event.tick}")
    }
  }

  /**
   * Calcula o próximo tempo global baseado no estado atual dos LocalTimeManagers
   */
  private def calculateNextGlobalTime(): Tick = {
    // Por enquanto, avanço incremental simples
    // Pode ser refinado baseado nas necessidades específicas dos LocalTimeManagers
    globalTime + 1
  }

  /**
   * Retorna estatísticas do GlobalTimeManager para debug
   */
  def getStatistics: String = {
    s"""GlobalTimeManager Statistics:
       |  Global Time: $globalTime
       |  Lower Bound Time Stamp: $lowerBoundTimeStamp
       |  Registered LocalTimeManagers: ${localTimeManagers.size}
       |  Current Phase: $synchronizationPhase
       |  Simulation Running: $isSimulationRunning""".stripMargin
  }
}

object GlobalTimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef
  ): Props =
    Props(classOf[GlobalTimeManager], simulationDuration, simulationManager)
}
