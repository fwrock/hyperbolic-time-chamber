package org.interscity.htc
package core.entity.event.control.simulation

import core.types.Tick
import org.apache.pekko.actor.ActorRef

/**
 * Eventos simplificados para Time-Stepped Simulation
 * 
 * Estes eventos são mais simples que o protocolo complexo criado anteriormente
 * e mantêm compatibilidade com o sistema existente
 */

/**
 * Comando para ator avançar para um tick específico (Time-Stepped)
 */
case class AdvanceToTick(targetTick: Tick) extends Serializable

/**
 * Confirmação de que ator completou processamento até um tick (Time-Stepped)
 */
case class TickCompleted(
  completedTick: Tick,
  actorId: String
) extends Serializable

/**
 * Evento para coordenação de barreira de sincronização (Time-Stepped)
 */
case class BarrierSynchronization(
  tick: Tick,
  expectedActors: Int
) extends Serializable

/**
 * Eventos para Time Window (implementação futura)
 */
case class WindowStart(
  startTick: Tick,
  windowSize: Tick
) extends Serializable

case class WindowEnd(
  endTick: Tick
) extends Serializable

/**
 * Evento otimístico com possível rollback (Time Window)
 */
case class OptimisticEvent(
  targetTick: Tick,
  rollbackTick: Option[Tick] = None
) extends Serializable
