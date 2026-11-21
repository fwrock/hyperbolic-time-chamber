package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick

import model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum

case class Phase(
  origin: Long,
  greenStart: Tick,
  greenDuration: Tick,
  state: TrafficSignalPhaseStateEnum
)
