package org.interscity.htc
package model.mobility.support

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
    reportFn: (Map[String, Any], String) => Unit,
    secondsPerTick: Double = 1.0,
    scheduledDeparture: Option[Tick] = None
  ): Unit = {
    val departed = departure >= 0
    val common: Map[String, Any] = Map(
      "vehicle_id" -> vehicleId, s"${vehicleType}_id" -> vehicleId,
      "origin" -> origin, "destination" -> destination,
      "final_node" -> null, "current_link" -> activeLink.map(Long.box).orNull,
      "completion_reason" -> (if (departed) "horizon_truncated" else "not_departed"), "horizon_truncated" -> true,
      "reached_destination" -> false, "tick" -> horizon
    ) ++ scheduledDeparture.map(planned => Map[String,Any](
      "scheduledDepart" -> (planned.toDouble*secondsPerTick),
      "actualDepart" -> (if (departed) departure.toDouble*secondsPerTick else -1.0),
      "departDelay" -> (if (departed) math.max(0L,departure-planned).toDouble*secondsPerTick else -1.0)
    )).getOrElse(Map.empty)
    reportFn(common ++ Map("event_type" -> "journey_completed", "total_distance" -> completedDistance),
      "journey_completed")
    // SUMO's -1 arrival denotes an unfinished trip; no position or partial-link distance is inferred.
    reportFn(common ++ Map(
      "event_type" -> "sumo_tripinfo", "vehicle_type" -> vehicleType, "vType" -> vehicleType,
      "depart" -> (if (departed) departure.toDouble * secondsPerTick else -1.0), "arrival" -> -1L,
      "duration" -> (if (departed) math.max(0L, horizon - departure).toDouble * secondsPerTick else -1.0),
      "routeLength" -> completedDistance, "vaporized" -> false
    ), "sumo_tripinfo")
  }
}
