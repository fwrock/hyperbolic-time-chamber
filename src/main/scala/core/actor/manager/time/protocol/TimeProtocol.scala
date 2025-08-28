package org.interscity.htc
package core.actor.manager.time.protocol

import core.types.Tick
import org.apache.pekko.actor.ActorRef

/**
 * Protocolo de comunicação entre GlobalTimeManager e LocalTimeManagers
 * 
 * Implementa o protocolo de sincronização de 4 fases:
 * 1. Request Time -> 2. Propose Time -> 3. Grant Time -> 4. Acknowledge
 */

// ==================== EVENTOS DE REGISTRO ====================

/**
 * Evento para registrar um LocalTimeManager no GlobalTimeManager
 */
case class RegisterLTMEvent(
  ltmType: String,  // "DES", "TimeStepped", "TimeWindow"
  ltmRef: ActorRef
)

/**
 * Confirmação de registro do LTM
 */
case class LTMRegistrationConfirmEvent(
  initialGlobalTime: Tick
)

// ==================== PROTOCOLO DE SINCRONIZAÇÃO (4 FASES) ====================

/**
 * FASE 1: Request Time
 * GTM -> LTM: Solicita o tempo atual do LTM
 */
case class TimeRequestEvent(
  globalTime: Tick
) {
  // Este evento vai do GTM para o LTM
  // O LTM deve responder com seu tempo atual
}

/**
 * Resposta do LTM para TimeRequestEvent
 * LTM -> GTM: Informa o tempo atual do LTM
 */
case class TimeResponseEvent(
  currentTime: Tick,
  hasScheduledEvents: Boolean = false
)

/**
 * FASE 2: Propose Time  
 * GTM -> LTM: Propõe um novo tempo baseado no LBTS calculado
 */
case class TimeProposeEvent(
  proposedTime: Tick,
  lbts: Tick  // Lower Bound Time Stamp
) {
  // O LTM deve responder se pode avançar até este tempo
}

/**
 * Resposta do LTM para TimeProposeEvent
 * LTM -> GTM: Confirma se pode avançar até o tempo proposto
 */
case class TimeProposeResponseEvent(
  proposedTime: Tick,
  canAdvance: Boolean,
  alternativeTime: Option[Tick] = None
)

/**
 * FASE 3: Grant Time
 * GTM -> LTM: Concede permissão para avançar até o tempo especificado
 */
case class GrantTimeAdvanceEvent(
  grantedTime: Tick
) {
  // O LTM deve processar até este tempo e enviar acknowledgment
}

/**
 * FASE 4: Acknowledge
 * LTM -> GTM: Confirma que processou até o tempo concedido
 */
case class TimeAcknowledgeEvent(
  processedUntilTime: Tick,
  hasMoreEvents: Boolean = false
)

// ==================== EVENTOS ESPECÍFICOS POR PARADIGMA ====================

/**
 * Eventos específicos para DES (Discrete Event Simulation)
 */
object DESProtocol {
  case class DESEventScheduled(
    eventTime: Tick,
    actorId: String
  )
  
  case class DESEventCompleted(
    completedTime: Tick,
    actorId: String
  )
}

/**
 * Eventos específicos para Time-Stepped
 */
object TimeSteppedProtocol {
  case class AdvanceToTick(
    targetTick: Tick
  )
  
  case class TickCompleted(
    completedTick: Tick,
    actorId: String
  )
  
  case class BarrierSynchronization(
    tick: Tick,
    actorsCount: Int
  )
}

/**
 * Eventos específicos para Time Window (implementação futura)
 */
object TimeWindowProtocol {
  case class WindowStart(
    startTime: Tick,
    windowSize: Tick
  )
  
  case class WindowEnd(
    endTime: Tick
  )
  
  case class OptimisticEvent(
    eventTime: Tick,
    rollbackPoint: Option[Tick]
  )
}

// ==================== EVENTOS DE CONTROLE GERAL ====================

/**
 * Evento para pausar a simulação
 */
case class PauseSimulationEvent()

/**
 * Evento para retomar a simulação
 */
case class ResumeSimulationEvent()

/**
 * Evento para parar a simulação
 */
case class StopSimulationEvent()

/**
 * Evento de status/debug
 */
case class RequestStatusEvent()

case class StatusResponseEvent(
  ltmType: String,
  currentTime: Tick,
  queueSize: Int,
  isActive: Boolean
)
