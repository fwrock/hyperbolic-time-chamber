package org.interscity.htc
package model.hybrid.support.emission

case class EmissionResult(
  co2Grams: Double,
  noxGrams: Double,
  pm25Grams: Double
) {
  def +(other: EmissionResult): EmissionResult =
    EmissionResult(
      co2Grams  = co2Grams  + other.co2Grams,
      noxGrams  = noxGrams  + other.noxGrams,
      pm25Grams = pm25Grams + other.pm25Grams
    )
}

object EmissionResult {
  val zero: EmissionResult = EmissionResult(0.0, 0.0, 0.0)
}
