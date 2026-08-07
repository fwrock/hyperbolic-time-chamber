package org.interscity.htc
package core.entity.event.control.warmup

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Sent by each WarmUpWorker to WarmUpManager when its local warm-up is complete. */
case class WarmUpWorkerDoneEvent(
  nodeAddress: String
) extends BaseEvent[DefaultBaseEventData]()
