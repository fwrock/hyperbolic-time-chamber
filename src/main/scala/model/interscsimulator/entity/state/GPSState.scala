package org.interscity.htc
package model.interscsimulator.entity.state

import core.entity.state.BaseState

import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.interscsimulator.types.CityMap

case class GPSState(
  startTick: Tick,
  cityMapPath: String = null,
  var cityMap: CityMap = null,
) extends BaseState(startTick = startTick)
