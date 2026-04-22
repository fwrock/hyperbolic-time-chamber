package org.interscity.htc
package model.hybrid.util

object BusUtil {

  def loadPersonTime(
    numberOfPassengers: Int,
    individualTime: Long = 5,
    numberOfPorts: Int,
    factor: Double = 1.5
  ): Long =
    Math.ceil((numberOfPassengers * (individualTime * factor)) / numberOfPorts).toLong

  def unloadPersonTime(
    numberOfPassengers: Int,
    individualTime: Long = 5,
    numberOfPorts: Int,
    factor: Double = 1.5
  ): Long =
    Math.ceil((numberOfPassengers * (individualTime * factor)) / numberOfPorts).toLong
}
