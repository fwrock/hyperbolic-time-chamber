package org.interscity.htc
package core.entity.event.control.load

import core.entity.configuration.Simulation

import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class SimulationConfigLoadedEvent(config: Simulation) extends BaseEvent[DefaultBaseEventData]
