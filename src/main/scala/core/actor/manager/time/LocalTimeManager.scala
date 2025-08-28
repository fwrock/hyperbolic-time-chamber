package org.interscity.htc
package core.actor.manager.time

import core.types.Tick
import core.actor.manager.time.protocol._
import core.actor.manager.BaseManager
import core.entity.state.DefaultState

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.event.control.execution.{ StartSimulationTimeEvent }
import org.htc.protobuf.core.entity.event.control.execution.{ PauseSimulationEvent => ProtoPauseSimulationEvent, ResumeSimulationEvent => ProtoResumeSimulationEvent, StopSimulationEvent => ProtoStopSimulationEvent }

// Eventos de status - definidos localmente para evitar problemas de dependência circular
case class RequestStatusEvent() extends Serializable
case class StatusResponseEvent(
  ltmType: String,
  currentTime: Tick,
  queueSize: Int,
  isActive: Boolean
) extends Serializable

/**
 * Trait base para todos os LocalTimeManagers (LTMs)
 * 
 * Define a interface de comunicação padrão com o GlobalTimeManager
 * e fornece a estrutura básica para diferentes paradigmas de simulação.
 */
abstract class LocalTimeManager(
  protected val globalTimeManager: ActorRef,
  protected val simulationDuration: Tick,
  protected val simulationManager: ActorRef,
  actorId: String
) extends BaseManager[DefaultState](
      timeManager = globalTimeManager,
      actorId = actorId
    ) {

  // Estado comum de todos os LTMs
  protected var currentLocalTime: Tick = 0
  protected var isRegisteredWithGTM: Boolean = false
  protected var isSimulationActive: Boolean = false
  protected var lastGrantedTime: Tick = 0

  // Tipo do LTM - deve ser sobrescrito por cada implementação
  def ltmType: String

  override def onStart(): Unit = {
    super.onStart()
    registerWithGlobalTimeManager()
  }

  /**
   * Registra este LTM com o GlobalTimeManager
   */
  private def registerWithGlobalTimeManager(): Unit = {
    logInfo(s"Registrando LTM do tipo '$ltmType' com o GlobalTimeManager")
    globalTimeManager ! RegisterLTMEvent(ltmType, self)
  }

  /**
   * Método base para handle de eventos - cada LTM especializado deve estender
   */
  override def handleEvent: Receive = baseTimeManagementReceive.orElse(specificTimeManagementReceive)

  /**
   * Receive básico para todos os LTMs - lida com o protocolo de sincronização
   */
  private def baseTimeManagementReceive: Receive = {
    // Confirmação de registro
    case confirm: LTMRegistrationConfirmEvent => 
      handleRegistrationConfirm(confirm)
    
    // Protocolo de sincronização de 4 fases
    case request: TimeRequestEvent => 
      handleTimeRequest(request)
    
    case propose: TimeProposeEvent => 
      handleTimePropose(propose)
    
    case grant: GrantTimeAdvanceEvent => 
      handleGrantTimeAdvance(grant)
    
    // Controle de simulação
    case start: StartSimulationTimeEvent => 
      handleStartSimulation(start)
    
    case ProtoPauseSimulationEvent => 
      pauseSimulation()
    
    case ProtoResumeSimulationEvent => 
      resumeSimulation()
    
    case ProtoStopSimulationEvent => 
      stopSimulation()
    
    case RequestStatusEvent => 
      respondStatus()
  }

  /**
   * Receive específico para cada tipo de LTM - deve ser implementado pelas subclasses
   */
  protected def specificTimeManagementReceive: Receive

  // ==================== HANDLERS DO PROTOCOLO DE SINCRONIZAÇÃO ====================

  /**
   * Trata confirmação de registro com o GTM
   */
  private def handleRegistrationConfirm(confirm: LTMRegistrationConfirmEvent): Unit = {
    isRegisteredWithGTM = true
    currentLocalTime = confirm.initialGlobalTime
    logInfo(s"LTM '$ltmType' registrado com sucesso. Tempo inicial: ${currentLocalTime}")
    onRegistrationComplete()
  }

  /**
   * FASE 1: Responde a solicitação de tempo do GTM
   */
  private def handleTimeRequest(request: TimeRequestEvent): Unit = {
    logDebug(s"Recebido TimeRequest para tempo global ${request.globalTime}")
    
    // Enviar tempo atual de volta para o GTM
    sender() ! TimeResponseEvent(
      currentTime = currentLocalTime,
      hasScheduledEvents = hasScheduledEvents()
    )
  }

  /**
   * FASE 2: Avalia proposta de tempo do GTM
   */
  private def handleTimePropose(propose: TimeProposeEvent): Unit = {
    logDebug(s"Recebido TimePropose: ${propose.proposedTime}, LBTS: ${propose.lbts}")
    
    val canAdvanceToProposedTime = canAdvanceToTime(propose.proposedTime)
    val alternativeTime = if (!canAdvanceToProposedTime) {
      getAlternativeTime(propose.proposedTime)
    } else None
    
    // Responder se pode avançar até o tempo proposto
    sender() ! TimeProposeResponseEvent(
      proposedTime = propose.proposedTime,
      canAdvance = canAdvanceToProposedTime,
      alternativeTime = alternativeTime
    )
  }

  /**
   * FASE 3: Executa avanço de tempo concedido pelo GTM
   */
  private def handleGrantTimeAdvance(grant: GrantTimeAdvanceEvent): Unit = {
    logDebug(s"Recebido GrantTimeAdvance para tempo ${grant.grantedTime}")
    
    lastGrantedTime = grant.grantedTime
    
    // Executar avanço de tempo específico do paradigma
    advanceToTime(grant.grantedTime)
    
    // Acknowledging que processou até o tempo concedido
    sender() ! TimeAcknowledgeEvent(
      processedUntilTime = grant.grantedTime,
      hasMoreEvents = hasScheduledEvents()
    )
    
    currentLocalTime = grant.grantedTime
  }

  /**
   * Inicia a simulação local
   */
  private def handleStartSimulation(start: StartSimulationTimeEvent): Unit = {
    currentLocalTime = start.startTick
    isSimulationActive = true
    logInfo(s"Simulação iniciada no LTM '$ltmType' no tempo ${start.startTick}")
    onSimulationStart(start)
  }

  /**
   * Pausa a simulação local
   */
  private def pauseSimulation(): Unit = {
    isSimulationActive = false
    logInfo(s"LTM '$ltmType' pausado")
    onSimulationPause()
  }

  /**
   * Retoma a simulação local
   */
  private def resumeSimulation(): Unit = {
    isSimulationActive = true
    logInfo(s"LTM '$ltmType' retomado")
    onSimulationResume()
  }

  /**
   * Para a simulação local
   */
  private def stopSimulation(): Unit = {
    isSimulationActive = false
    logInfo(s"LTM '$ltmType' parado no tempo ${currentLocalTime}")
    onSimulationStop()
    context.stop(self)
  }

  /**
   * Responde com status atual
   */
  private def respondStatus(): Unit = {
    sender() ! StatusResponseEvent(
      ltmType = ltmType,
      currentTime = currentLocalTime,
      queueSize = getQueueSize(),
      isActive = isSimulationActive
    )
  }

  // ==================== MÉTODOS ABSTRATOS - DEVEM SER IMPLEMENTADOS PELAS SUBCLASSES ====================

  /**
   * Verifica se há eventos agendados no LTM
   */
  protected def hasScheduledEvents(): Boolean

  /**
   * Verifica se pode avançar até o tempo especificado
   */
  protected def canAdvanceToTime(targetTime: Tick): Boolean

  /**
   * Sugere um tempo alternativo se não pode avançar até o tempo proposto
   */
  protected def getAlternativeTime(proposedTime: Tick): Option[Tick]

  /**
   * Executa o avanço de tempo específico do paradigma
   */
  protected def advanceToTime(targetTime: Tick): Unit

  /**
   * Retorna o tamanho da fila/número de eventos pendentes
   */
  protected def getQueueSize(): Int

  // ==================== HOOKS PARA SUBCLASSES ====================

  /**
   * Chamado quando o registro com o GTM é completado
   */
  protected def onRegistrationComplete(): Unit = {}

  /**
   * Chamado quando a simulação inicia
   */
  protected def onSimulationStart(start: StartSimulationTimeEvent): Unit = {}

  /**
   * Chamado quando a simulação é pausada
   */
  protected def onSimulationPause(): Unit = {}

  /**
   * Chamado quando a simulação é retomada
   */
  protected def onSimulationResume(): Unit = {}

  /**
   * Chamado quando a simulação para
   */
  protected def onSimulationStop(): Unit = {}

  // ==================== UTILITÁRIOS ====================

  /**
   * Verifica se a simulação está ativa
   */
  protected def isActive: Boolean = isSimulationActive && isRegisteredWithGTM

  /**
   * Obtém tempo local atual
   */
  protected def getCurrentTime: Tick = currentLocalTime

  /**
   * Obtém último tempo concedido pelo GTM
   */
  protected def getLastGrantedTime: Tick = lastGrantedTime
}
