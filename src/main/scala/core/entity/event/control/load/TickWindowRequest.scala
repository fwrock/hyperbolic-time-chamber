package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick

/**
 * Sent by GlobalTimeManager to ProgressiveLoadDataManager to request
 * that all actors with startTick <= horizonTick be created and initialized.
 *
 * @param currentTick  the tick currently being processed by the simulation
 * @param horizonTick  the look-ahead tick boundary (currentTick + lookAheadTicks)
 */
case class TickWindowRequest(
  currentTick: Tick,
  horizonTick: Tick
)
