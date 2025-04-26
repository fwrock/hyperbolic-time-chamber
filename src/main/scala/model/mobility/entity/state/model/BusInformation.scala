package org.interscity.htc
package model.mobility.entity.state.model

case class BusInformation(
  actorId: String,
  label: String = null,
  capacity: Int,
  numberOfPorts: Int,
  size: Double
)
