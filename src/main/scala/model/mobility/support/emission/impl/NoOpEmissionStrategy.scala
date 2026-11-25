package org.interscity.htc
package model.mobility.support.emission.impl

import model.mobility.support.emission.{EmissionResult, MesoEmissionStrategy, MicroEmissionStrategy}

object NoOpEmissionStrategy extends MicroEmissionStrategy with MesoEmissionStrategy {
  override val microModelName: String = "none"
  override val mesoModelName: String  = "none"
  override def computeMicro(velocityMs: Double, accelerationMs2: Double, dtSeconds: Double): EmissionResult = EmissionResult.zero
  override def computeMeso(distanceMeters: Double, dtSeconds: Double): EmissionResult = EmissionResult.zero
}
