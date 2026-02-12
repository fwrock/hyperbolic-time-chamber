package org.interscity.htc
package core.entity.event.control.execution

import core.types.Tick

/** Event to notify local time managers about a new time window
  * 
  * @param windowStart
  *   Start tick of the window (inclusive)
  * @param windowEnd
  *   End tick of the window (exclusive)
  */
case class UpdateGlobalTimeWindow(
  windowStart: Tick,
  windowEnd: Tick,
  totalEventsAmount: Long,
  totalActorsAmount: Long,
  startTime: Long = 0,
  isStart: Boolean = false
)
