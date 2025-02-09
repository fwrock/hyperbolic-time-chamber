package org.interscity.htc
package model.interscsimulator.entity.event.data.signal

import org.interscity.htc.core.types.CoreTypes.Tick
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.state.model.SignalState

case class TrafficSignalChangeStatusData(
  signalState: SignalState,
  nextTick: Tick,
  phaseOrigin: String
) extends BaseEventData
