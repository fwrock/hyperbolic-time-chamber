package org.interscity.htc
package model.interscsimulator.entity.event.data.bus

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.BaseEventData

case class BusRequestUnloadPassengerData(
  nodeId: String,
  nodeRef: ActorRef
) extends BaseEventData
