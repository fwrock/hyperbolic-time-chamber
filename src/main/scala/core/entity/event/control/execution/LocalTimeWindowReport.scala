package org.interscity.htc
package core.entity.event.control.execution

import core.types.Tick

/** Report from local time manager about window completion
  * 
  * @param windowEnd
  *   End tick of the completed window
  * @param hasScheduled
  *   Whether there are scheduled events beyond this window
  * @param actorRef
  *   Path of the reporting local time manager
  */
case class LocalTimeWindowReport(
  windowEnd: Tick,
  hasScheduled: Boolean,
  actorRef: String,
  eventsAmount: Long = 0,
  actorsAmount: Long = 0
)
