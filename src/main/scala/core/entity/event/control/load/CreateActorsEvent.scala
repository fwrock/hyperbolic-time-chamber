package org.interscity.htc
package core.entity.event.control.load

import core.entity.actor.ActorSimulation

import core.entity.event.BaseEvent

case class CreateActorsEvent(
  actors: List[ActorSimulation]
) extends BaseEvent()
