package org.interscity.htc
package model.hybrid.entity.state

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import core.entity.state.BaseState
import core.runtime.EventOrder
import core.types.Tick

final case class RoutingCostVersion(weight: Double, order: EventOrder)

final case class RoutingServiceState(
  startTick: Tick = 0L,
  scheduleOnTimeManager: Boolean = false,
  @JsonDeserialize(keyAs = classOf[java.lang.Long])
  costs: Map[Long, RoutingCostVersion] = Map.empty
) extends BaseState(startTick = startTick, scheduleOnTimeManager = scheduleOnTimeManager)
