package org.interscity.htc
package model.mobility.entity.event.data.signal

import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.entity.event.data.BaseEventData
import model.mobility.entity.state.model.SignalState

case class TrafficSignalChangeStatusData(
  signalState: SignalState,
  nextTick: Tick,
  phaseOrigin: Long,
  updates: List[SignalAspectUpdate] = Nil
) extends BaseEventData
