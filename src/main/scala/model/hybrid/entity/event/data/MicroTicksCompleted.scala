package org.interscity.htc
package model.hybrid.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Signal for completing all micro ticks in a global tick.
  *
  * Broadcast from link to all vehicles when global tick completes.
  *
  * @param linkId
  *   Link completing tick
  * @param globalTick
  *   Global tick number
  * @param totalSubTicks
  *   Number of sub-ticks executed
  */
case class MicroTicksCompleted(
  linkId: String,
  globalTick: Long,
  totalSubTicks: Int
) extends BaseEventData
