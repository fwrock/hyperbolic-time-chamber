package org.interscity.htc
package model.mobility.entity.event.data

import org.interscity.htc.core.entity.event.data.BaseEventData

/** Event data emitted by Link on every vehicle enter/leave.
  * Used by ClickHouseReportData to build vehicle flow time series.
  *
  * @param linkId             The link actor id (serves as the series key)
  * @param eventType          "enter" or "leave"
  * @param vehicleId          The vehicle/movable actor id
  * @param actorType          Vehicle mode string (Car, Bus, Bicycle, etc.)
  * @param actorCreationType  How the actor was created (string)
  * @param vehicleCountOnLink Instantaneous count at the moment of the event
  */
case class VehicleLinkFlowData(
  linkId: String,
  eventType: String,
  vehicleId: String,
  actorType: String,
  actorCreationType: String,
  vehicleCountOnLink: Int
) extends BaseEventData
