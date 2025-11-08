package org.interscity.htc
package model.hybrid.entity.state.model

case class EdgeGraph(
  id: String,
  resourceId: String,
  classType: String,
  length: Double
) {
  override def toString: String = f"EdgeLabel($id, len=$length%.2f)"
}
