package org.interscity.htc
package model.mobility.entity.state.model

case class NodeGraph(
  id: String,
  shardId: String,
  classType: String,
  latitude: Double,
  longitude: Double
) {
  override def toString: String = s"Node($id)"

  def euclideanDistance(other: NodeGraph): Double = {
    val latDiff = latitude - other.latitude
    val lonDiff = longitude - other.longitude
    math.sqrt(latDiff * latDiff + lonDiff * lonDiff)
  }
}
