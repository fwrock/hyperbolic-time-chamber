package org.interscity.htc
package model.interscsimulator.entity.event.data.subway

import core.entity.event.data.BaseEventData

case class RegisterSubwayPassengerData(
  line: String
) extends BaseEventData
