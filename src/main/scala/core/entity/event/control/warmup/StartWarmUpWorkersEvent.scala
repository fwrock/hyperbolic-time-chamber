package org.interscity.htc
package core.entity.event.control.warmup

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Sent by WarmUpManager to each WarmUpWorker to start local warm-up. */
case class StartWarmUpWorkersEvent(
  targets: List[String]
) extends BaseEvent[DefaultBaseEventData]()
