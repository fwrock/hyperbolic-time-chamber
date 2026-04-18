package org.interscity.htc
package core.entity.control.loadbalance

/** Generic interface for any entity that exists in a 2D spatial domain.
  *
  * This abstraction allows the load balancer to work with any spatial simulation, not just
  * mobility. Implementations include:
  *   - Mobility: Cars, Buses, Links, Nodes (lat/lon)
  *   - Physics: Particles, agents in grid (x/y)
  *   - Generic: Any entity with a 2D position
  */
trait SpatialEntity {

  /** Unique identifier of this spatial entity. */
  def spatialEntityId: String

  /** Current position as (x, y) or (longitude, latitude). */
  def position: (Double, Double)

  /** Optional bounding box for entities that occupy area (e.g., Links). If None, entity is treated
    * as a point.
    */
  def bounds: Option[SpatialBounds] = None

  /** Estimated computational cost of this entity per tick. Default is 1.0 (uniform cost).
    * Override for entities with known variable cost (e.g., micro-mode links cost more).
    */
  def computationalWeight: Double = 1.0
}
