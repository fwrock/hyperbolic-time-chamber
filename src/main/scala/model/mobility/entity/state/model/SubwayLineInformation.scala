package org.interscity.htc
package model.mobility.entity.state.model

import core.types.Tick

case class SubwayLineInformation(
  interval: Tick,
  var nextTick: Tick = -1
)
