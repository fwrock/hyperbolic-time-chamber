package org.interscity.htc
package model.mobility.entity.event.data.link

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.data.BaseEventData

case class LinkConnectionsData(
  to: Identify,
  from: Identify
) extends BaseEventData
