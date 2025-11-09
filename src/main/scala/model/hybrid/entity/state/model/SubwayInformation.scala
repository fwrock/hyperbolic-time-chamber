package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick

case class SubwayInformation(
  line: String,
  actorId: String,
  capacity: Int,
  numberOfPorts: Int,
  velocity: Double,
  stopTime: Tick
)
