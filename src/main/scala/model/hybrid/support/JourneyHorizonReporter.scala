package org.interscity.htc
package model.hybrid.support

import core.types.Tick

object JourneyHorizonReporter {
  def report(
    vehicleType: String,
    vehicleId: String,
    origin: Long,
    destination: Long,
    departure: Tick,
    horizon: Tick,
    completedDistance: Double,
    activeLink: Option[Long],
    reportFn: (Map[String, Any], String) => Unit
  ): Unit = {
    val common: Map[String, Any] = Map(
      "vehicle_id" -> vehicleId, s"${vehicleType}_id" -> vehicleId,
      "origin" -> origin, "destination" -> destination,
      "final_node" -> null, "current_link" -> activeLink.map(Long.box).orNull,
      "completion_reason" -> "horizon_truncated", "horizon_truncated" -> true,
      "reached_destination" -> false, "tick" -> horizon
    )
    reportFn(common ++ Map("event_type" -> "journey_completed", "total_distance" -> completedDistance),
      "journey_completed")
    // SUMO's -1 arrival denotes an unfinished trip; no position or partial-link distance is inferred.
    reportFn(common ++ Map(
      "event_type" -> "sumo_tripinfo", "vehicle_type" -> vehicleType, "vType" -> vehicleType,
      "depart" -> departure, "arrival" -> -1L, "duration" -> math.max(0L, horizon - departure),
      "routeLength" -> completedDistance, "vaporized" -> false
    ), "sumo_tripinfo")
  }
}
