package org.interscity.htc
package model.hybrid.entity.event.data

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.data.BaseEventData
import model.hybrid.entity.state.model.RoutePathItem

import scala.collection.mutable

final case class ReceiveRouteData(
  path: mutable.Queue[(Identify, Identify)],
  label: String,
  origin: String,
  destination: String
) extends BaseEventData
