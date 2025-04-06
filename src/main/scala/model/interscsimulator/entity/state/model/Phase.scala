package org.interscity.htc
package model.interscsimulator.entity.state.model

import core.types.Tick

import model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum

case class Phase(
  origin: String,
  greenStart: Tick,
  greenDuration: Tick,
  state: TrafficSignalPhaseStateEnum
)
