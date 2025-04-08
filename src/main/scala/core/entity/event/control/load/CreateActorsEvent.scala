package org.interscity.htc
package core.entity.event.control.load

import core.entity.actor.ActorSimulationCreation
import core.entity.event.BaseEvent

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class CreateActorsEvent(
  actors: Seq[ActorSimulationCreation],
  actorRef: ActorRef
) extends BaseEvent[DefaultBaseEventData]()
