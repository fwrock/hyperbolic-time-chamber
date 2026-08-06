package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Event sent by TimeManager to LoadBalanceManager confirming that the simulation is paused at a
  * safe tick boundary and migration can proceed.
  *
  * @param currentTick
  *   The tick at which the TimeManager paused
  */
case class MigrationSafeEvent(
  currentTick: Long
) extends BaseEvent[DefaultBaseEventData]
