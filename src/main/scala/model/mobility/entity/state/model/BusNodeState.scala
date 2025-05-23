package org.interscity.htc
package model.mobility.entity.state.model

import core.types.Tick

case class BusNodeState(
  var timeToLoadedPassengers: Tick = Long.MinValue,
  var timeToUnloadedPassengers: Tick = Long.MinValue,
  var timeToOpenSignal: Tick = Long.MinValue
)
