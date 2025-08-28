package org.interscity.htc
package model.mobility.entity.event.data.link

import org.interscity.htc.core.entity.event.data.BaseEventData

final case class LinkInfoData(
  linkLength: Double = 0,
  linkCapacity: Double = 0,
  linkNumberOfCars: Int = 0,
  linkFreeSpeed: Double = 0,
  linkLanes: Int = 0,
  linkCurrentSpeed: Option[Double] = None,
  linkCongestionFactor: Option[Double] = None
) extends BaseEventData
