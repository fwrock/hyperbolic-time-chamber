package org.interscity.htc
package model.hybrid.entity.state.model

/** A transit stop (bus stop or subway station) in the transit network.
  *
  * Used by [[org.interscity.htc.model.hybrid.util.TransitMapUtil]] to build a lightweight index of
  * public-transport access points, separate from the full road-network graph. Distances between
  * stops and origin/destination nodes are computed via haversine rather than network routing, which
  * is an order of magnitude cheaper and sufficient for mode-choice scoring.
  *
  * @param id
  *   Unique stop identifier (e.g. `"htcaid:stop;bus_stop_123"`).
  * @param actorId
  *   Actor entity ID to send `RegisterPassenger` messages to (e.g. `"htcaid:busstop;123"`).
  * @param actorClassType
  *   Shard class type for actor routing (e.g. `"hybrid.actor.BusStop"`).
  * @param nodeId
  *   Road-network node ID where this stop is located (e.g. `"htcaid:node;456"`).
  * @param latitude
  *   WGS-84 latitude of the stop.
  * @param longitude
  *   WGS-84 longitude of the stop.
  * @param stopType
  *   Transport mode served: `"bus"` or `"subway"`.
  * @param lines
  *   List of line labels served by this stop (e.g. `["line_1", "line_2"]`).
  */
case class TransitStop(
  id: String,
  actorId: String,
  actorClassType: String,
  nodeId: String,
  latitude: Double,
  longitude: Double,
  stopType: String,
  lines: List[String] = List.empty
)
