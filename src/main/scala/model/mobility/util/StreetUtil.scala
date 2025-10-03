package org.interscity.htc
package model.mobility.util

object StreetUtil {

  private val cellSize = 7.5
  private val digitalRailsCellSize = 4.0

  def calcCapacity(length: Double, lanes: Int): Double = {
    val baseCapacityPerLane = 2000.0
    val lengthFactor = if (length <= 100) 1.0 else if (length <= 500) 0.9 else 0.8
    baseCapacityPerLane * lanes * lengthFactor
  }
}
