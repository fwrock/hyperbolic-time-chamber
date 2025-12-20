package org.interscity.htc
package model.mobility.util

object SpeedUtil {

  // Performance counters
  @volatile private var speedCalculationCount: Long = 0L
  @volatile private var capacityExceededCount: Long = 0L

  def linkDensitySpeed(
    length: Double,
    capacity: Double,
    numberOfCars: Long,
    freeSpeed: Double,
    lanes: Int = 1
  ): Double = {
    speedCalculationCount += 1
    
    if numberOfCars >= capacity then {
      capacityExceededCount += 1
      1.0
    }
    else {
      // Optimized: alpha=1.0 → pow(x, 1.0) = x (eliminates outer pow)
      // Only need: freeSpeed * (1 - pow(density, beta))
      val density = numberOfCars.toDouble / capacity
      val beta = 0.05
      freeSpeed * (1.0 - math.pow(density, beta))
    }
  }

  def printSpeedCalculationStats(): Unit = {
    println(s"\n=== Speed Calculation Statistics ===")
    println(s"Total speed calculations: $speedCalculationCount")
    println(s"Capacity exceeded: $capacityExceededCount")
    if (speedCalculationCount > 0) {
      val exceededRate = (capacityExceededCount.toDouble / speedCalculationCount) * 100
      println(f"Capacity exceeded rate: $exceededRate%.2f%%")
    }
    println(s"====================================\n")
  }

  def resetStats(): Unit = {
    speedCalculationCount = 0L
    capacityExceededCount = 0L
  }

  /*
   *link_density_speed(Id, Length, Capacity, NumberCars, Freespeed, _Lanes) ->

	Alpha = 1,
	Beta = 1,
	Speed = case NumberCars >= Capacity of
		true -> 1.0;
		false -> Freespeed * math:pow(1 - math:pow((NumberCars / Capacity), Beta), Alpha)
	end,

	Time = (Length / Speed) + 1,
	{Id, round(Time), round(Length)}.
   * */
}
