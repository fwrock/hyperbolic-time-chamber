package org.interscity.htc
package model.hybrid.entity.event.data

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.data.BaseEventData
import model.hybrid.entity.state.model.RoutePathItem

import scala.collection.mutable

final case class RequestRouteData(
  requester: ActorRef,
  requesterId: String,
  requesterClassType: String,
  targetNodeId: String,
  currentCost: Double,
  originNodeId: String,
  path: mutable.Queue[(Identify, Identify)],
  label: String = "default"
) extends BaseEventData
