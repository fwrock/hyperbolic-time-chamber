package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class PrepareSimulationEvent(
  configuration: String = null
) extends BaseEvent[DefaultBaseEventData]
