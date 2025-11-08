package org.interscity.htc
package model.hybrid.entity.event.data.subway

import core.entity.event.data.BaseEventData

case class RegisterSubwayPassengerData(
  line: String
) extends BaseEventData
