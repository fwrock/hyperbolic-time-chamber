package org.interscity.htc
package model.hybrid.entity.event.data.link

import core.entity.event.data.BaseEventData
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum

case class LinkSignalStateData(
  phase: TrafficSignalPhaseStateEnum,
  nextTick: Tick
) extends BaseEventData
