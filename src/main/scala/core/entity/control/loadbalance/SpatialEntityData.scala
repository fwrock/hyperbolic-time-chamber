package org.interscity.htc
package core.entity.control.loadbalance

/** Simple case class implementing [[SpatialEntity]] for batch registration.
  *
  * Used by [[core.actor.manager.load.CreatorLoadData]] when registering entities with
  * [[core.actor.manager.loadbalance.LoadBalanceManager]] during creation. This is a lightweight
  * DTO — the actual domain state (NodeState, LinkState, etc.) is created later during actor
  * initialization.
  *
  * @param spatialEntityId
  *   Unique entity identifier (e.g., "htcaid:node;12345")
  * @param position
  *   2D position as (x/longitude, y/latitude)
  * @param computationalWeight
  *   Estimated cost per tick (default 1.0)
  */
case class SpatialEntityData(
  spatialEntityId: String,
  position: (Double, Double),
  override val computationalWeight: Double = 1.0
) extends SpatialEntity
