package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent
import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.control.execution.data.StartSimulationData

case class StartSimulationEvent(
  startTick: Tick = 0,
  actorRef: ActorRef = null,
  data: StartSimulationData = StartSimulationData()
) extends BaseEvent[StartSimulationData](tick = startTick, data = data, actorRef = actorRef)
