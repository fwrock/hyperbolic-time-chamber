package org.interscity.htc
package model.hybrid.entity.event.data.signal

import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.entity.event.data.BaseEventData
import model.hybrid.entity.state.model.SignalState

case class TrafficSignalChangeStatusData(
  signalState: SignalState,
  nextTick: Tick,
  phaseOrigin: String
) extends BaseEventData
