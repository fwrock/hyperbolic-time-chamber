package org.interscity.htc
package model.interscsimulator.entity.event.data

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable

final case class RequestRouteData(
  requester: ActorRef,
  requesterId: String,
  targetNodeId: String,
  currentCost: Double,
  path: mutable.Queue[(RoutePathItem, RoutePathItem)]
) extends BaseEventData
