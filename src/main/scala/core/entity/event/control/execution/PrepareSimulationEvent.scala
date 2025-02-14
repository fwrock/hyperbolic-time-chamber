package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent

case class PrepareSimulationEvent(
  configuration: String = null
) extends BaseEvent
