package org.interscity.htc
package model.interscsimulator.entity.state.model

import core.types.CoreTypes.Tick

case class BusNodeState(
  var timeToLoadedPassengers: Tick = Long.MinValue,
  var timeToUnloadedPassengers: Tick = Long.MinValue,
  var timeToOpenSignal: Tick = Long.MinValue
)
