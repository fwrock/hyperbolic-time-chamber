package org.interscity.htc
package model.hybrid.util

import core.types.Tick

object SubwayUtil {

  def timeToNextStation(
    distance: Double,
    velocity: Double
  ): Tick = {
    require(distance.isFinite && distance >= 0.0 && velocity.isFinite && velocity > 0.0,
      "subway travel requires nonnegative metres and positive metres per second")
    Math.ceil(distance / velocity).toLong
  }

  def numberOfPassengerToBoarding(
    numberOfPorts: Int,
    portsCapacity: Int,
    stopTime: Tick,
    boardingTimeByPassenger: Double
  ): Int =
    Math
      .ceil(
        numberOfPorts * portsCapacity * (stopTime / boardingTimeByPassenger)
      )
      .toInt
}
