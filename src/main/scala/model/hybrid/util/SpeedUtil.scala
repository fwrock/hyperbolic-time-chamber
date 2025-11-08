package org.interscity.htc
package model.hybrid.util

object SpeedUtil {

  def linkDensitySpeed(
    length: Double,
    capacity: Double,
    numberOfCars: Long,
    freeSpeed: Double,
    lanes: Int = 1
  ): Double = {
    val alpha = 1.0
    val beta = 0.05
    if numberOfCars >= capacity then 1.0
    else freeSpeed * math.pow(1 - math.pow(numberOfCars / capacity, beta), alpha)
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
