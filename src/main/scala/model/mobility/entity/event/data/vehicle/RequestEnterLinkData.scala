package org.interscity.htc
package model.mobility.entity.event.data.vehicle

import core.entity.event.data.BaseEventData
import core.types.Tick

/** Event-driven: Vehicle requests entry to link with travel time calculation
  *
  * Replaces tick-driven polling pattern. Link will calculate travel time
  * based on current density and respond with EnterLinkConfirmData.
  *
  * @param vehicleId     Vehicle requesting entry
  * @param entryTick     Tick when vehicle wants to enter
  * @param destinationNode Target node within link
  */
case class RequestEnterLinkData(
  vehicleId: String,
  entryTick: Tick,
  destinationNode: String
) extends BaseEventData
