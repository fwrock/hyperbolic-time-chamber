package org.interscity.htc
package model.hybrid.support.emission.impl

import model.hybrid.support.emission.{EmissionResult, MesoEmissionStrategy}

/** Computer Programme to calculate Emissions from Road Transport (COPERT) —
  * average-speed emission model (European Environment Agency, COPERT 5).
  *
  * Emission factor (g/km):
  *   emissionFactor(averageSpeed) = a/averageSpeed + b + c*averageSpeed + d*averageSpeed²
  * where averageSpeed is in km/h. Separate factor curves for CO₂, NOₓ, PM₂.₅.
  * Override defaults via params keys: co2_a/b/c/d, nox_a/b/c/d, pm25_a/b/c/d.
  */
class CopertEmissionModel private (
  private val co2Coeffs:  Array[Double],
  private val noxCoeffs:  Array[Double],
  private val pm25Coeffs: Array[Double]
) extends MesoEmissionStrategy {

  override val mesoModelName: String = "copert"

  override def computeMeso(distanceMeters: Double, dtSeconds: Double): EmissionResult = {
    if (distanceMeters <= 0.0 || dtSeconds <= 0.0) return EmissionResult.zero
    val averageSpeedKmh = (distanceMeters / dtSeconds) * 3.6
    val distanceKm      = distanceMeters / 1000.0

    EmissionResult(
      co2Grams  = emissionFactor(co2Coeffs,  averageSpeedKmh) * distanceKm,
      noxGrams  = emissionFactor(noxCoeffs,  averageSpeedKmh) * distanceKm,
      pm25Grams = emissionFactor(pm25Coeffs, averageSpeedKmh) * distanceKm
    )
  }

  private def emissionFactor(coefficients: Array[Double], averageSpeedKmh: Double): Double =
    math.max(0.0,
      coefficients(0) / math.max(1.0, averageSpeedKmh) +
      coefficients(1) +
      coefficients(2) * averageSpeedKmh +
      coefficients(3) * averageSpeedKmh * averageSpeedKmh
    )
}

object CopertEmissionModel {

  // Default COPERT 5 hot emission factors — Euro-4 gasoline passenger car.
  // CO₂ (g/km): ~170 at 50 km/h, rises at low and very high speeds.
  private val DefaultCo2  = Array(500.0, 120.0, 1.0,   0.01)
  // NOₓ (g/km): ~0.12 at 50 km/h
  private val DefaultNox  = Array(1.2,   0.05,  0.002, 0.00001)
  // PM₂.₅ (g/km): ~0.002 at 50 km/h (gasoline, very low)
  private val DefaultPm25 = Array(0.005, 0.001, 0.0,   0.0)

  def apply(params: Map[String, String]): CopertEmissionModel = {
    def d(key: String, default: Double): Double =
      params.get(key).flatMap(s => scala.util.Try(s.toDouble).toOption).getOrElse(default)

    new CopertEmissionModel(
      co2Coeffs  = Array(d("co2_a",  DefaultCo2(0)),  d("co2_b",  DefaultCo2(1)),  d("co2_c",  DefaultCo2(2)),  d("co2_d",  DefaultCo2(3))),
      noxCoeffs  = Array(d("nox_a",  DefaultNox(0)),  d("nox_b",  DefaultNox(1)),  d("nox_c",  DefaultNox(2)),  d("nox_d",  DefaultNox(3))),
      pm25Coeffs = Array(d("pm25_a", DefaultPm25(0)), d("pm25_b", DefaultPm25(1)), d("pm25_c", DefaultPm25(2)), d("pm25_d", DefaultPm25(3)))
    )
  }
}
