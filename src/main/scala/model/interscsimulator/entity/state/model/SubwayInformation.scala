package org.interscity.htc
package model.interscsimulator.entity.state.model

import core.types.CoreTypes.Tick

case class SubwayInformation(
  line: String,
  actorId: String,
  capacity: Int,
  numberOfPorts: Int,
  velocity: Double,
  stopTime: Tick
)
