package org.interscity.htc
package model.hybrid.support.emission

import model.hybrid.support.emission.impl.{CopertEmissionModel, NoOpEmissionStrategy}

trait MesoEmissionStrategy {

  /** Calculates emission for a full meso link traversal.
    * @param distanceMeters link length in metres
    * @param dtSeconds      total travel time in seconds
    */
  def computeMeso(distanceMeters: Double, dtSeconds: Double): EmissionResult

  def mesoModelName: String
}

object MesoEmissionStrategy {

  def byType(modelType: MesoEmissionModelType, params: Map[String, String]): MesoEmissionStrategy =
    modelType match {
      case MesoEmissionModelType.Copert => CopertEmissionModel(params)
      case MesoEmissionModelType.None   => NoOpEmissionStrategy
    }

  def fromConfigKey(key: String, params: Map[String, String]): MesoEmissionStrategy =
    byType(MesoEmissionModelType.fromConfigKey(key), params)

  val none: MesoEmissionStrategy = NoOpEmissionStrategy
}
