package org.interscity.htc
package model.interscsimulator.util

import org.interscity.htc.model.interscsimulator.entity.state.model.{ Location, PointOfInterest }

import scala.math.{ atan2, cos, sin, sqrt, toRadians }

object GeolocationUtil {

  private val EARTH_RADIUS = 6371

  def haversine(
    location1: Location,
    location2: Location
  ): Double = {
    val dLat = toRadians(location2.latitude - location1.latitude)
    val dLon = toRadians(location2.longitude - location1.longitude)
    val a = sin(dLat / 2) * sin(dLat / 2) +
      cos(toRadians(location1.latitude)) * cos(toRadians(location2.latitude)) *
      sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    EARTH_RADIUS * c
  }

  def pointsInRadius(
    origin: Location,
    destination: Location,
    points: List[PointOfInterest],
    radius: Double
  ): List[PointOfInterest] =
    points.filter {
      point =>
        val distToOrigin = haversine(origin, point.location)
        val distToDest = haversine(destination, point.location)

        distToOrigin <= radius && distToDest <= radius
    }
}
