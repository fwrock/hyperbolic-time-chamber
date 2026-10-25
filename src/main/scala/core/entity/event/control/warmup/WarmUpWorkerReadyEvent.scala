package org.interscity.htc
package core.entity.event.control.warmup

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

case class WarmUpWorkerReadyEvent(nodeAddress: String) extends BaseEvent[DefaultBaseEventData]()
