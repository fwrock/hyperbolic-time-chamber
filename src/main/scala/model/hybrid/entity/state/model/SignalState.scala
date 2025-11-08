package org.interscity.htc
package model.hybrid.entity.state.model

import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum

import org.interscity.htc.core.types.Tick

case class SignalState(
  var state: TrafficSignalPhaseStateEnum,
  var remainingTime: Tick,
  var nextTick: Tick
)
