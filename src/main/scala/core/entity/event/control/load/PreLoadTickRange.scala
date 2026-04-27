package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick

/** Internal event sent from ProgressiveLoadDataManager to itself to trigger pre-loading of actors
  * for a specific tick range.
  *
  * @param fromTick
  *   start of the tick range to load (inclusive)
  * @param toTick
  *   end of the tick range to load (inclusive)
  */
case class PreLoadTickRange(
  fromTick: Tick,
  toTick: Tick
)
