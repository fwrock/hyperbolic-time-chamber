package org.interscity.htc
package model.interscsimulator.entity.state.model

import core.types.CoreTypes.Tick

import model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum

case class Phase(
  origin: String,
  greenStart: Tick,
  greenDuration: Tick,
  state: TrafficSignalPhaseStateEnum
)
