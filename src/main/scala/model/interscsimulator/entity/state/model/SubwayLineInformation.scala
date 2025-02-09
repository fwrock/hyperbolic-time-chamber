package org.interscity.htc
package model.interscsimulator.entity.state.model

import core.types.CoreTypes.Tick

case class SubwayLineInformation(
  interval: Tick,
  var nextTick: Tick = -1
)
