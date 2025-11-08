package org.interscity.htc
package model.hybrid.entity.state.model

case class NodeGraph(
  id: String,
  resourceId: String,
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
