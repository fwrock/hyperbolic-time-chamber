package org.interscity.htc
package model.hybrid.entity.event.data.person

import core.entity.event.data.BaseEventData

/** Message from Person to Vehicle to park (deactivate).
  *
  * Optional message if Person needs to explicitly park a vehicle before it completes its trip
  * naturally.
  *
  * @param personId
  *   ID of the person
  * @param parkingNodeId
  *   Node where vehicle should park
  */
case class ParkVehicleData(
  personId: String,
  parkingNodeId: String
) extends BaseEventData
