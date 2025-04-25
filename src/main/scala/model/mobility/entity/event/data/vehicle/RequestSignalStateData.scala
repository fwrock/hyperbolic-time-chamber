package org.interscity.htc
package model.mobility.entity.event.data.vehicle

import org.interscity.htc.core.entity.event.data.BaseEventData

case class RequestSignalStateData(
  targetLinkId: String
) extends BaseEventData
