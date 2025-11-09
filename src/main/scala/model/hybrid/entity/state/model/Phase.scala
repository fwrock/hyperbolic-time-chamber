package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick

import model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum

case class Phase(
  origin: String,
  greenStart: Tick,
  greenDuration: Tick,
  state: TrafficSignalPhaseStateEnum
)
