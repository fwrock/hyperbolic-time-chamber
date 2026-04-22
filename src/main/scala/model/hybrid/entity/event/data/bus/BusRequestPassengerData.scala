package org.interscity.htc
package model.hybrid.entity.event.data.bus

import core.entity.event.data.BaseEventData

case class BusRequestPassengerData(
  label: String,
  availableSpace: Int
) extends BaseEventData
