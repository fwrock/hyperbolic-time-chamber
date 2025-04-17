package org.interscity.htc
package core.entity.event.control.report

import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class ReportEvent(
  entityId: String,
  tick: Long,
  lamportTick: Long,
  timestamp: Long = System.nanoTime(),
  data: Any
) extends BaseEvent[DefaultBaseEventData]
