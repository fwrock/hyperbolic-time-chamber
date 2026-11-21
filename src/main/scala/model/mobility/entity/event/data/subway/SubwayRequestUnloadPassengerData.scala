package org.interscity.htc
package model.mobility.entity.event.data.subway

import core.entity.event.data.BaseEventData

import org.apache.pekko.actor.ActorRef

case class SubwayRequestUnloadPassengerData(
  nodeId: Long,
  nodeRef: ActorRef,
  vehicleDistance: Double = 0.0
) extends BaseEventData
