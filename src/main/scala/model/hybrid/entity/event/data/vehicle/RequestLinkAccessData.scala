package org.interscity.htc
package model.hybrid.entity.event.data.vehicle

import org.interscity.htc.core.entity.event.data.BaseEventData

/** MICRO Link authorizes crossing within its physics interval; Node still owns downstream capacity.
  * The clearance survives reporting that crossing at the interval's rounded-up global tick.
  */
case class RequestLinkAccessData(
  targetLinkId: Long,
  signalAlreadyCleared: Boolean = false
) extends BaseEventData
