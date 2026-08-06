package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Event sent by LoadBalanceManager to TimeManager after all pending migrations have completed,
  * indicating that the TimeManager can resume advancing ticks.
  */
case class MigrationCompleteNotifyEvent(
) extends BaseEvent[DefaultBaseEventData]
