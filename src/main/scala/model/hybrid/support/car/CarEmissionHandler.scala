package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import org.interscity.htc.model.hybrid.support.emission.{EmissionResult, MesoEmissionStrategy, MicroEmissionStrategy}

/** Handles per-vehicle emission calculation for hybrid (micro + meso) Car actors.
  *
  * Micro path: accumulates EmissionResult across sub-ticks using real (v, a) pairs;
  * flushes the accumulated total on MicroLeaveLink with a single report() call.
  *
  * Meso path: computes emission on LeaveLinkInfo using average-speed derivation and
  * reports immediately (no sub-tick accumulation needed).
  *
  * Accumulator vars are per-link-traversal and reset on each EnterLink / MicroEnterLink.
  */
class CarEmissionHandler(
  private val microStrategy: MicroEmissionStrategy,
  private val mesoStrategy: MesoEmissionStrategy,
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick
) {

  private var microTimeStep: Double       = 1.0
  private var accumulated: EmissionResult = EmissionResult.zero
  private var subTickCount: Int           = 0

  def onMicroEnterLink(linkId: String, microTimeStepSeconds: Double): Unit = {
    microTimeStep = math.max(0.001, microTimeStepSeconds)
    accumulated   = EmissionResult.zero
    subTickCount  = 0
  }

  def onMicroUpdate(velocityMs: Double, accelerationMs2: Double): Unit = {
    accumulated  = accumulated + microStrategy.computeMicro(velocityMs, accelerationMs2, microTimeStep)
    subTickCount += 1
  }

  def onMicroLeaveLink(linkId: String, distanceTraveled: Double, travelTimeSeconds: Double): Unit = {
    if (accumulated == EmissionResult.zero) return

    reportFn(
      Map(
        "event_type"     -> "emission_micro_leave_link",
        "car_id"         -> entityIdFn(),
        "link_id"        -> linkId,
        "mode"           -> "MICRO",
        "model"          -> microStrategy.microModelName,
        "sub_tick_count" -> subTickCount,
        "distance_m"     -> distanceTraveled,
        "travel_time_s"  -> travelTimeSeconds,
        "co2_grams"      -> accumulated.co2Grams,
        "nox_grams"      -> accumulated.noxGrams,
        "pm25_grams"     -> accumulated.pm25Grams,
        "tick"           -> currentTickFn()
      ),
      "emission_micro_leave_link"
    )

    accumulated  = EmissionResult.zero
    subTickCount = 0
  }

  def onLeaveLink(linkId: String, distanceMeters: Double, travelTimeSeconds: Double): Unit = {
    val result = mesoStrategy.computeMeso(distanceMeters, travelTimeSeconds)
    if (result == EmissionResult.zero) return

    reportFn(
      Map(
        "event_type"    -> "emission_leave_link",
        "car_id"        -> entityIdFn(),
        "link_id"       -> linkId,
        "mode"          -> "MESO",
        "model"         -> mesoStrategy.mesoModelName,
        "distance_m"    -> distanceMeters,
        "travel_time_s" -> travelTimeSeconds,
        "co2_grams"     -> result.co2Grams,
        "nox_grams"     -> result.noxGrams,
        "pm25_grams"    -> result.pm25Grams,
        "tick"          -> currentTickFn()
      ),
      "emission_leave_link"
    )
  }

  def onSignalWait(waitTicks: Long, timeStepSeconds: Double = 1.0): Unit = {
    val duration = waitTicks.toDouble * timeStepSeconds
    val result   = microStrategy.computeMicro(0.0, 0.0, duration)
    if (result == EmissionResult.zero) return
    reportFn(
      Map(
        "event_type" -> "emission_signal_idle",
        "car_id"     -> entityIdFn(),
        "duration_s" -> duration,
        "co2_grams"  -> result.co2Grams,
        "nox_grams"  -> result.noxGrams,
        "pm25_grams" -> result.pm25Grams,
        "tick"       -> currentTickFn()
      ),
      "emission_signal_idle"
    )
  }

  def reset(): Unit = {
    accumulated   = EmissionResult.zero
    subTickCount  = 0
    microTimeStep = 1.0
  }
}
