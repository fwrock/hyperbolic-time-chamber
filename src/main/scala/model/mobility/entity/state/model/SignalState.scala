package org.interscity.htc
package model.mobility.entity.state.model

import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum

import org.interscity.htc.core.types.Tick

case class SignalState(
  var state: TrafficSignalPhaseStateEnum,
  var remainingTime: Tick,
  var nextTick: Tick
)
