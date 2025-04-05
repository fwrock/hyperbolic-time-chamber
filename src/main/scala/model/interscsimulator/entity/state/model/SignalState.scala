package org.interscity.htc
package model.interscsimulator.entity.state.model

import model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum

import org.interscity.htc.core.types.Tick

case class SignalState(
  var state: TrafficSignalPhaseStateEnum,
  var remainingTime: Tick,
  var nextTick: Tick
)
