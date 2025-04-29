package org.interscity.htc
package model.mobility.entity.event.node

import core.entity.event.data.BaseEventData

import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum

case class SignalStateData(
  phase: TrafficSignalPhaseStateEnum,
  nextTick: Tick
) extends BaseEventData
