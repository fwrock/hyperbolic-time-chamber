package org.interscity.htc
package model.hybrid.support.emission

import model.hybrid.support.emission.impl.{NoOpEmissionStrategy, VirginiaTechMicroEmissionModel}

trait MicroEmissionStrategy {

  /** Calculates instantaneous emission for one micro sub-tick.
    * @param velocityMs      current speed in m/s
    * @param accelerationMs2 current acceleration in m/s²
    * @param dtSeconds       sub-tick duration in seconds
    */
  def computeMicro(velocityMs: Double, accelerationMs2: Double, dtSeconds: Double): EmissionResult

  def microModelName: String
}

object MicroEmissionStrategy {

  def byType(modelType: MicroEmissionModelType, params: Map[String, String]): MicroEmissionStrategy =
    modelType match {
      case MicroEmissionModelType.VirginiaTechMicro => VirginiaTechMicroEmissionModel(params)
      case MicroEmissionModelType.None              => NoOpEmissionStrategy
    }

  def fromConfigKey(key: String, params: Map[String, String]): MicroEmissionStrategy =
    byType(MicroEmissionModelType.fromConfigKey(key), params)

  val none: MicroEmissionStrategy = NoOpEmissionStrategy
}
