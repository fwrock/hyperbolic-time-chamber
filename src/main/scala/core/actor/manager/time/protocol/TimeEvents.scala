package org.interscity.htc
package core.actor.manager.time.protocol

import core.types.Tick

/**
 * Protocolo de eventos para coordenação TimeStepped
 */
case class AdvanceToTick(targetTick: Tick)

case class TickCompleted(completedTick: Tick, actorId: String)

/**
 * Protocolo de eventos para coordenação TimeWindow
 */
case class WindowStart(startTick: Tick, windowSize: Tick)

case class WindowEnd(endTick: Tick, windowSize: Tick)

case class OptimisticEvent(
  targetTick: Tick, 
  rollbackTick: Option[Tick] = None,
  stateSnapshot: Option[Any] = None
)
