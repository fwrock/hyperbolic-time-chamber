package org.interscity.htc
package model.interscsimulator.entity.event.node

import core.entity.event.data.BaseEventData

import org.interscity.htc.core.types.CoreTypes.Tick
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum

case class SignalStateData(
  phase: TrafficSignalPhaseStateEnum,
  nextTick: Tick
) extends BaseEventData
