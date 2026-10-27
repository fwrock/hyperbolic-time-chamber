package org.interscity.htc
package core.entity.control.loadbalance

import core.enumeration.ShardTypeEnum
import java.util.Locale

object SpatialEntityClassification {
  private val staticTypes = Set("node", "link", "raillink", "trafficsignal", "signal",
    "busstop", "busstation", "subwaystation", "routingservice")

  def classify(entity: SpatialEntity): ShardTypeEnum = {
    val classType = Option(entity.entityClassType).map(_.trim).filter(_.nonEmpty)
    val kind = classType.map(ct => ct.substring(ct.lastIndexOf('.') + 1)).getOrElse {
      val id = entity.spatialEntityId
      val colon = id.indexOf(':')
      val semicolon = id.indexOf(';')
      if (colon >= 0 && semicolon > colon) id.substring(colon + 1, semicolon) else ""
    }.toLowerCase(Locale.ROOT).replace("_", "")
    if (staticTypes.contains(kind)) ShardTypeEnum.Static else ShardTypeEnum.Dynamic
  }
}
