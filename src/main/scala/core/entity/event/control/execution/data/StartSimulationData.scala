package org.interscity.htc
package core.entity.event.control.execution.data

import core.entity.event.data.BaseEventData

final case class StartSimulationData(
  startTime: Long = System.currentTimeMillis()
) extends BaseEventData
