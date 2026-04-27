package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Event to trigger a rebalancing cycle in the LoadBalanceManager. Can be sent periodically or by
  * the SimulationManager.
  */
case class TriggerRebalanceEvent(
  reason: String = "periodic"
) extends BaseEvent[DefaultBaseEventData]
