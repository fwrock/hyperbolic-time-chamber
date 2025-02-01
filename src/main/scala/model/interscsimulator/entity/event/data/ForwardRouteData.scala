package org.interscity.htc
package model.interscsimulator.entity.event.data

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable

final case class ForwardRouteData(
  requester: ActorRef = null,
  requesterId: String = null,
  updatedCost: Double = 0,
  targetNodeId: String = null,
  path: mutable.Queue[(RoutePathItem, RoutePathItem)]
) extends BaseEventData
