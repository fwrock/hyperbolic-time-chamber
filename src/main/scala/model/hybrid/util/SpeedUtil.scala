package org.interscity.htc
package model.hybrid.util

object SpeedUtil {

  /** BPR (Bureau of Public Roads, 1964) volume-delay function — the standard link-performance
    * congestion multiplier for traffic assignment/routing cost, still the default volume-delay
    * function in most static and dynamic traffic assignment tools. `alpha`/`beta` default to the
    * original BPR coefficients (0.15, 4.0).
    *
    * Deliberately separate from [[linkDensitySpeed]]: that function drives the actual simulated
    * vehicle speed (an ad hoc alpha=beta=1 density-speed relationship); this one is the routing
    * *cost* signal published in [[org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost]],
    * which is meant to penalize near-/over-capacity links more sharply than linear.
    */
  def bprCongestionFactor(volume: Double, capacity: Double, alpha: Double = 0.15, beta: Double = 4.0): Double =
    if (capacity <= 0) 1.0
    else 1.0 + alpha * math.pow(volume / capacity, beta)

  def linkDensitySpeed(
    length: Double,
    capacity: Double,
    numberOfCars: Long,
    freeSpeed: Double,
    lanes: Int = 1
  ): Double = {
    val alpha = 1.0
    val beta = 1.0
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
