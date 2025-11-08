package org.interscity.htc
package model.hybrid.entity.event.node

import core.entity.event.data.BaseEventData

import org.interscity.htc.core.types.Tick
import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum

case class SignalStateData(
  phase: TrafficSignalPhaseStateEnum,
  nextTick: Tick
) extends BaseEventData
