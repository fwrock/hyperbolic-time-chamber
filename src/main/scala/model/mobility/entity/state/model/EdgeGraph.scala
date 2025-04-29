package org.interscity.htc
package model.mobility.entity.state.model

case class EdgeGraph(
  id: String,
  shardId: String,
  classType: String,
  length: Double
) {
  override def toString: String = f"EdgeLabel($id, len=$length%.2f)"
}
