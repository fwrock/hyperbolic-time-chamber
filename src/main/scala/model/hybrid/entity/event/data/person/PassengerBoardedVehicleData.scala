package org.interscity.htc
package model.hybrid.entity.event.data.person

import core.entity.event.data.BaseEventData

/** Sent by Bus or Subway to Person immediately after loading the passenger.
  *
  * Person stores this so that onDestruct can send an isArrival=false response to the PT vehicle
  * if Person dies while still on board — preventing the unload-counter deadlock on the vehicle
  * side.
  *
  * @param vehicleId
  *   Entity ID of the PT vehicle that loaded this passenger
  * @param vehicleClassType
  *   Shard / class type of the vehicle (e.g. "hybrid.actor.Bus" or "hybrid.actor.Subway")
  */
case class PassengerBoardedVehicleData(
  vehicleId: String,
  vehicleClassType: String
) extends BaseEventData
