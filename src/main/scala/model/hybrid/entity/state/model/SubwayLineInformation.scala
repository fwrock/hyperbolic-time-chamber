package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick

case class SubwayLineInformation(
  interval: Tick,
  var nextTick: Tick = -1
)
