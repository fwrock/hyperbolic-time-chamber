package org.interscity.htc
package model.hybrid.support.emission

/** Meso emission model strategy types.
  * Copert = Computer Programme to calculate Emissions from Road Transport (European Environment Agency).
  */
enum MesoEmissionModelType(val configKey: String):
  case Copert extends MesoEmissionModelType("copert")
  case None   extends MesoEmissionModelType("none")

object MesoEmissionModelType:
  def fromConfigKey(key: String): MesoEmissionModelType =
    values.find(_.configKey == key.toLowerCase).getOrElse(None)
