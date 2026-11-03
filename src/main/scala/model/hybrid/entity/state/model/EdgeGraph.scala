package org.interscity.htc
package model.hybrid.entity.state.model

case class EdgeGraph(
  id: Long,
  resourceId: String,
  classType: String,
  length: Double,
  sidewalk: SidewalkConfig = SidewalkConfig.Default
) {
  require(sidewalk != null, "sidewalk configuration cannot be null")
  override def toString: String = f"EdgeLabel($id, len=$length%.2f)"
}
