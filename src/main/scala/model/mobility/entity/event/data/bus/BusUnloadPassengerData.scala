package org.interscity.htc
package model.mobility.entity.event.data.bus

import core.entity.event.data.BaseEventData

case class BusUnloadPassengerData(
  isArrival: Boolean = false
) extends BaseEventData
