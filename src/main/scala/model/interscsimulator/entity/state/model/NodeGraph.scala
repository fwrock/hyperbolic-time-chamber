package org.interscity.htc
package model.interscsimulator.entity.state.model

case class NodeGraph(
  id: String,
  shardId: String,
  classType: String,
  latitude: Double,
  longitude: Double
) {
  override def toString: String = s"Node($id)"
}
