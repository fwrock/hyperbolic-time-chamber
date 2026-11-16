package org.interscity.htc
package model.hybrid.entity.state.model

object MicroSpeed {
  def limit(roadSpeedMps: Double, maxSpeed: Option[Double], speedFactor: Double = 1.0,
            desiredMaxSpeed: Option[Double] = None): Double =
    math.min(maxSpeed.getOrElse(Double.PositiveInfinity),
      speedFactor * math.min(roadSpeedMps, desiredMaxSpeed.getOrElse(roadSpeedMps)))
}
