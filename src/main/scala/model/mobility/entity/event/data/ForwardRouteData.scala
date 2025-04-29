package org.interscity.htc
package model.mobility.entity.event.data

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.mobility.entity.state.model.RoutePathItem

import scala.collection.mutable

final case class ForwardRouteData(
  requester: ActorRef = null,
  requesterId: String = null,
  updatedCost: Double = 0,
  targetNodeId: String = null,
  path: mutable.Queue[(Identify, Identify)]
) extends BaseEventData
