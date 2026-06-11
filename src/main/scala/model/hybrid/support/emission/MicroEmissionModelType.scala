package org.interscity.htc
package model.hybrid.support.emission

enum MicroEmissionModelType(val configKey: String):
  case VirginiaTechMicro extends MicroEmissionModelType("virginia_tech_micro")
  case None              extends MicroEmissionModelType("none")

object MicroEmissionModelType:
  def fromConfigKey(key: String): MicroEmissionModelType =
    values.find(_.configKey == key.toLowerCase).getOrElse(None)
