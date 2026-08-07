package org.interscity.htc
package core.entity.event.control.warmup

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData

/** Sent by WarmUpManager to SimulationManager when ALL workers have completed. */
case class WarmUpAllDoneEvent() extends BaseEvent[DefaultBaseEventData]()
