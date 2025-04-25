package org.interscity.htc
package model.interscsimulator.entity.state

import core.entity.state.BaseState

import org.interscity.htc.core.types.Tick

case class GPSState(
  startTick: Tick,
  simulationPath: String = null
) extends BaseState(startTick = startTick)
