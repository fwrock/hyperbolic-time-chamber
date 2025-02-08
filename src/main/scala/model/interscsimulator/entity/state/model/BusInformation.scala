package org.interscity.htc
package model.interscsimulator.entity.state.model

case class BusInformation(
  actorId: String,
  busType: String = null,
  capacity: Int,
  size: Double
)
