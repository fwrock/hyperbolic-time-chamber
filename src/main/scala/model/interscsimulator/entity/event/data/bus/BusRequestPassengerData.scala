package org.interscity.htc
package model.interscsimulator.entity.event.data.bus

import core.entity.event.data.BaseEventData

case class BusRequestPassengerData(
  label: String,
  availableSpace: Int
) extends BaseEventData
