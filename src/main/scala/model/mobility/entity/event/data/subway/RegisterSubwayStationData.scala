package org.interscity.htc
package model.mobility.entity.event.data.subway

import core.entity.event.data.BaseEventData

case class RegisterSubwayStationData(
  lines: Seq[String]
) extends BaseEventData
