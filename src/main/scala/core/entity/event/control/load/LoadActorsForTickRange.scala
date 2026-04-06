package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick
import core.entity.actor.ActorSimulation

/**
 * Internal event sent to ProgressiveJsonLoadData to request
 * loading actors for a specific tick range from the pre-built index.
 *
 * @param fromTick  start of the tick range (inclusive)
 * @param toTick    end of the tick range (inclusive)
 */
case class LoadActorsForTickRange(
  fromTick: Tick,
  toTick: Tick
)
