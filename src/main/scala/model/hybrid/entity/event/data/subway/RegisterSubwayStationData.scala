package org.interscity.htc
package model.hybrid.entity.event.data.subway

import core.entity.event.data.BaseEventData

case class RegisterSubwayStationData(
  lines: Seq[String]
) extends BaseEventData
