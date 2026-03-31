package org.interscity.htc
package core.entity.event.control.loadbalance

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData
import core.entity.control.loadbalance.SpatialEntity

/** Event to register a spatial entity with the LoadBalanceManager.
  *
  * @param entity
  *   The spatial entity to register in the partitioning structure
  */
case class RegisterSpatialEntityEvent(
  entity: SpatialEntity
) extends BaseEvent[DefaultBaseEventData]
