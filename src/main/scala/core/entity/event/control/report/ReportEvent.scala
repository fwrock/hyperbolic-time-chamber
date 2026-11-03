package org.interscity.htc
package core.entity.event.control.report

import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class ReportEvent(
  entityId: String,
  tick: Long,
  lamportTick: Long,
  timestamp: Long = System.nanoTime(),
  data: Any,
  label: String = null,
  // Distributed-run context (core.cluster.NodeIdentity stamps = rank + 1, 0 = none): `node` is the node of the
  // reporting actor (default evaluated where the report is created); `fromNode` is the node that sent the message
  // being handled when the report was made, 0 when the report comes from a spontaneous event.
  node: Int = core.cluster.NodeIdentity.selfStamp,
  fromNode: Int = 0,
  // Type of the message being handled (its event type) and the sender's actor class; "" for spontaneous events.
  messageType: String = "",
  fromType: String = ""
) extends BaseEvent[DefaultBaseEventData]
