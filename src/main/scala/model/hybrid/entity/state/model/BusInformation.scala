package org.interscity.htc
package model.hybrid.entity.state.model

case class BusInformation(
  actorId: String,
  label: String = null,
  capacity: Int,
  numberOfPorts: Int,
  size: Double,
  /** Speed factor applied to the desired velocity in MICRO mode. Values < 1.0 make the bus
    * more conservative; values > 1.0 make it more aggressive. Default: 1.0 (nominal).
    */
  speedFactor: Double = 1.0
)
