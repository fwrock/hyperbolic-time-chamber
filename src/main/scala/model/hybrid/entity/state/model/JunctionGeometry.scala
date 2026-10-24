package org.interscity.htc
package model.hybrid.entity.state.model

final case class MetricPoint(x: Double, y: Double) {
  require(x.isFinite && y.isFinite, "junction coordinates must be finite metres")
}

final case class JunctionGeometry(
  nodeId: Long,
  incomingLinkId: Long,
  outgoingLinkId: Long,
  lengthMeters: Double,
  speedLimitMps: Double,
  shape: List[MetricPoint]
) {
  require(lengthMeters.isFinite && lengthMeters > 0.0, "junction length must be positive metres")
  require(speedLimitMps.isFinite && speedLimitMps > 0.0, "junction speed must be positive m/s")
  require(shape.size >= 2, "junction path needs at least two points")
  private def segments = shape.iterator.zip(shape.iterator.drop(1))
  def shapeLengthMeters: Double = segments.map { case (a, b) => math.hypot(b.x - a.x, b.y - a.y) }.sum
  require(shapeLengthMeters.isFinite && shapeLengthMeters > 0.0, "junction path must have finite extent")

  def positionAt(distanceMeters: Double): MetricPoint = {
    require(distanceMeters.isFinite, "junction position must be finite")
    // Serialized lane length and rounded polyline length can differ; preserve both endpoints.
    var remaining = math.max(0.0, math.min(lengthMeters, distanceMeters)) / lengthMeters * shapeLengthMeters
    val parts = segments
    while (parts.hasNext) {
      val (a, b) = parts.next()
      val length = math.hypot(b.x - a.x, b.y - a.y)
      if (length > 0.0 && remaining <= length) {
        val fraction = remaining / length
        return MetricPoint(a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y))
      }
      remaining -= length
    }
    shape.last
  }
}

final case class JunctionMovement(incomingLinkId: Long, outgoingLinkId: Long, connectorLinkId: Long) {
  require(connectorLinkId != incomingLinkId && connectorLinkId != outgoingLinkId,
    "junction connector must be distinct from its roads")
}

object JunctionGeometry {
  def reportingFields(position: Option[MetricPoint]): Map[String, Any] =
    position.map(p => Map[String, Any]("junction" -> true, "junction_x" -> p.x, "junction_y" -> p.y))
      .getOrElse(Map.empty)
}
