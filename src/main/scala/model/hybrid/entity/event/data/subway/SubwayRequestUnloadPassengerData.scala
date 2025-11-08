package org.interscity.htc
package model.hybrid.entity.event.data.subway

import core.entity.event.data.BaseEventData

import org.apache.pekko.actor.ActorRef

case class SubwayRequestUnloadPassengerData(
  nodeId: String,
  nodeRef: ActorRef
) extends BaseEventData
