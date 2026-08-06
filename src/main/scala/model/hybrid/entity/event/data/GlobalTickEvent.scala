package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Global tick trigger event from TimeManager to Link.
  *
  * Sent from LocalTimeManager to micro-enabled links to trigger sub-tick execution for the current
  * global tick.
  *
  * @param tick
  *   Global tick number to execute
  */
case class GlobalTickEvent(
  tick: Long
) extends BaseEventData
