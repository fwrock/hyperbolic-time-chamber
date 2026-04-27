package org.interscity.htc
package core.entity.control.loadbalance

/** Axis-aligned bounding rectangle in 2D space.
  *
  * Used for quadtree partitioning and spatial queries. Coordinates can represent lat/lon for
  * geographic simulations or arbitrary x/y for abstract spatial models.
  *
  * @param minX
  *   Minimum X coordinate (e.g., min longitude)
  * @param minY
  *   Minimum Y coordinate (e.g., min latitude)
  * @param maxX
  *   Maximum X coordinate (e.g., max longitude)
  * @param maxY
  *   Maximum Y coordinate (e.g., max latitude)
  */
case class SpatialBounds(
  minX: Double,
  minY: Double,
  maxX: Double,
  maxY: Double
) {

  /** Center X coordinate */
  def centerX: Double = (minX + maxX) / 2.0

  /** Center Y coordinate */
  def centerY: Double = (minY + maxY) / 2.0

  /** Width of the bounding box */
  def width: Double = maxX - minX

  /** Height of the bounding box */
  def height: Double = maxY - minY

  /** Area of the bounding box */
  def area: Double = width * height

  /** Checks if a point (x, y) is contained within these bounds. */
  def contains(x: Double, y: Double): Boolean =
    x >= minX && x <= maxX && y >= minY && y <= maxY

  /** Checks if this bounds intersects with another bounds. */
  def intersects(other: SpatialBounds): Boolean =
    !(other.minX > maxX || other.maxX < minX || other.minY > maxY || other.maxY < minY)

  /** Returns the four quadrant sub-bounds (NW, NE, SW, SE). */
  def quadrants: (SpatialBounds, SpatialBounds, SpatialBounds, SpatialBounds) = {
    val cx = centerX
    val cy = centerY
    (
      SpatialBounds(minX, cy, cx, maxY), // NW
      SpatialBounds(cx, cy, maxX, maxY), // NE
      SpatialBounds(minX, minY, cx, cy), // SW
      SpatialBounds(cx, minY, maxX, cy) // SE
    )
  }
}
