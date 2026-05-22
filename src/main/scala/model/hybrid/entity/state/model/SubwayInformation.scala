package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick

case class SubwayInformation(
  line: String,
  actorId: String,
  capacity: Int,
  numberOfPorts: Int,
  velocity: Double,
  stopTime: Tick,
  /** Speed factor applied to the desired velocity. Values < 1.0 make the train
    * more conservative; values > 1.0 make it more aggressive. Default: 1.0 (nominal).
    */
  speedFactor: Double = 1.0
)
