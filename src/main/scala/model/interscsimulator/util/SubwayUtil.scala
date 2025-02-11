package org.interscity.htc
package model.interscsimulator.util

import core.types.CoreTypes.Tick

object SubwayUtil {

  def timeToNextStation(
    distance: Double,
    velocity: Double
  ): Tick = Math.ceil((distance / velocity) * 3600).toLong

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
