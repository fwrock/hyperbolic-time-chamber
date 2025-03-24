package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class PauseSimulationEvent() extends BaseEvent[DefaultBaseEventData](tick = -1, actorRef = null)
