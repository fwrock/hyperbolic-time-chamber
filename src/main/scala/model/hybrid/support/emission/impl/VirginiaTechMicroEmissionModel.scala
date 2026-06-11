package org.interscity.htc
package model.hybrid.support.emission.impl

import model.hybrid.support.emission.{EmissionResult, MicroEmissionStrategy}

/** Virginia Tech Microscale instantaneous emission model (Rakha et al., 2004).
  *
  * Computes fuel consumption rate (mL/s) as:
  *   fuelConsumptionRate = exp(Σ_{i=0}^{3} L_i * v^i  +  Σ_{i=0}^{3} A_i * v^i * a)
  * where v is in km/h, a in m/s², with separate coefficient sets for
  * acceleration (a ≥ 0) and deceleration (a < 0) regimes.
  *
  * CO₂ and co-pollutant rates are derived from fuel consumption rate using fixed
  * conversion factors for a gasoline passenger car. Override defaults via
  * `params` keys: L0..L3 / A0..A3 (accel regime), DL0..DL3 / DA0..DA3 (decel).
  */
class VirginiaTechMicroEmissionModel private (
  private val lAcc: Array[Double],
  private val aAcc: Array[Double],
  private val lDec: Array[Double],
  private val aDec: Array[Double],
  private val co2PerMlFuel: Double,
  private val noxPerMlFuel: Double,
  private val pm25PerMlFuel: Double
) extends MicroEmissionStrategy {

  override val microModelName: String = "virginia_tech_micro"

  override def computeMicro(velocityMs: Double, accelerationMs2: Double, dtSeconds: Double): EmissionResult = {
    if (velocityMs <= 0.0) return EmissionResult.zero

    val velocityKmh = velocityMs * 3.6
    val (lCoeffs, aCoeffs) = if (accelerationMs2 >= 0.0) (lAcc, aAcc) else (lDec, aDec)

    val velocityPoly     = lCoeffs(0) + lCoeffs(1) * velocityKmh + lCoeffs(2) * velocityKmh * velocityKmh + lCoeffs(3) * velocityKmh * velocityKmh * velocityKmh
    val accelerationPoly = (aCoeffs(0) + aCoeffs(1) * velocityKmh + aCoeffs(2) * velocityKmh * velocityKmh + aCoeffs(3) * velocityKmh * velocityKmh * velocityKmh) * math.abs(accelerationMs2)

    val fuelConsumptionRateMlPerSec = math.max(0.0, math.exp(velocityPoly + accelerationPoly))
    val fuelConsumptionTotal        = fuelConsumptionRateMlPerSec * dtSeconds

    EmissionResult(
      co2Grams  = fuelConsumptionTotal * co2PerMlFuel,
      noxGrams  = fuelConsumptionTotal * noxPerMlFuel,
      pm25Grams = fuelConsumptionTotal * pm25PerMlFuel
    )
  }
}

object VirginiaTechMicroEmissionModel {

  // Default coefficients — light-duty gasoline passenger car, calibrated in km/h / m/s² units.
  // Source: Rakha et al. (2004), Table 1, approximated for generality.
  private val DefaultLAcc = Array(-2.974, 0.0861, -0.000954, 3.63e-6)
  private val DefaultAAcc = Array(0.154,  0.00107, -1.6e-5,   6.7e-8)
  private val DefaultLDec = Array(-2.974, 0.0861, -0.000954, 3.63e-6)
  private val DefaultADec = Array(-0.08,  0.0,     0.0,       0.0)

  // Gasoline: 0.74 g/mL density × 0.866 carbon fraction × (44/12) CO₂/C ratio
  private val DefaultCo2PerMl  = 2.35
  // Typical Euro-4 gasoline NOₓ ≈ 0.3% of fuel mass
  private val DefaultNoxPerMl  = 0.00294
  // Gasoline PM₂.₅ ≈ 0.003% of fuel mass
  private val DefaultPm25PerMl = 0.0000294

  def apply(params: Map[String, String]): VirginiaTechMicroEmissionModel = {
    def d(key: String, default: Double): Double =
      params.get(key).flatMap(s => scala.util.Try(s.toDouble).toOption).getOrElse(default)

    new VirginiaTechMicroEmissionModel(
      lAcc = Array(d("L0", DefaultLAcc(0)), d("L1", DefaultLAcc(1)), d("L2", DefaultLAcc(2)), d("L3", DefaultLAcc(3))),
      aAcc = Array(d("A0", DefaultAAcc(0)), d("A1", DefaultAAcc(1)), d("A2", DefaultAAcc(2)), d("A3", DefaultAAcc(3))),
      lDec = Array(d("DL0", DefaultLDec(0)), d("DL1", DefaultLDec(1)), d("DL2", DefaultLDec(2)), d("DL3", DefaultLDec(3))),
      aDec = Array(d("DA0", DefaultADec(0)), d("DA1", DefaultADec(1)), d("DA2", DefaultADec(2)), d("DA3", DefaultADec(3))),
      co2PerMlFuel  = d("co2_per_ml",  DefaultCo2PerMl),
      noxPerMlFuel  = d("nox_per_ml",  DefaultNoxPerMl),
      pm25PerMlFuel = d("pm25_per_ml", DefaultPm25PerMl)
    )
  }
}
