package org.interscity.htc
package model.interscsimulator.entity.state.model

import model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum

import org.interscity.htc.core.types.CoreTypes.Tick

case class SignalState(
  var state: TrafficSignalPhaseStateEnum,
  var remainingTime: Tick
)
