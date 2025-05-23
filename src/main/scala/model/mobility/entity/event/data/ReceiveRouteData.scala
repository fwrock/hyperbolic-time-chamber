package org.interscity.htc
package model.mobility.entity.event.data

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.mobility.entity.state.model.RoutePathItem

import scala.collection.mutable

final case class ReceiveRouteData(
  path: mutable.Queue[(Identify, Identify)],
  label: String,
  origin: String,
  destination: String
) extends BaseEventData
