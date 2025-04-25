package org.interscity.htc
package model.mobility.util

object SpeedUtil {

  def linkDensitySpeed(
    length: Double,
    capacity: Double,
    numberOfCars: Long,
    freeSpeed: Double,
    lanes: Int = 1
  ): Double = {
    val alpha = 1
    val beta = 1
    if numberOfCars >= capacity then 1.0
    else freeSpeed * math.pow(1 - math.pow(numberOfCars / capacity * lanes, beta), alpha)
  }
}
