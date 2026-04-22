package org.interscity.htc
package core.entity.event.control.load

import core.types.Tick

/**
 * Sent by ProgressiveJsonLoadData to ProgressiveLoadDataManager
 * when a specific tick range has been fully loaded from a source file.
 *
 * @param sourceId      identifier of the data source
 * @param fromTick      start tick of the range
 * @param toTick        end tick of the range
 * @param actorsLoaded  number of actors sent to creators in this range
 */
case class TickRangeLoadedEvent(
  sourceId: String,
  fromTick: Tick,
  toTick: Tick,
  actorsLoaded: Long
)
