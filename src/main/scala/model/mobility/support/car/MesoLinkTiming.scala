package org.interscity.htc
package model.mobility.support.car

import core.types.Tick

/** Fields every MESO vehicle keeps so the rounding remainder survives checkpoints. */
trait MesoTimingState {
  var mesoRoundingCredit: Double
  var mesoLastExitTick: Long
}

/** Exit tick of a MESO link traversal.
  *
  * Default: the traversal time rounded up to whole ticks for every link, which adds about half a tick per link.
  * With `carry`: the rounded-up remainder (credit) is carried into the next link, so the cumulative exit time follows the continuous time within one tick;
  * a wait at a node (signal or capacity) absorbs the credit. At least one tick per link is kept either way.
  */
object MesoLinkTiming {

  /** Seconds a Car needs for one MESO link, from the link's own snapshot; the Car and the Link both use it. */
  def carTravelSeconds(length: Double, capacity: Double, numberOfCars: Long, freeSpeed: Double, lanes: Int): Double =
    length / (model.mobility.util.SpeedUtil.linkEntryDensitySpeed(
      length = length, capacity = capacity, numberOfCars = numberOfCars, freeSpeed = freeSpeed, lanes = lanes) / 3.6)

  /** The exit tick the vehicle would compute itself, from the rounding state it sent along. */
  def delegatedExitTick(tick: Tick, seconds: Double, entry: model.mobility.entity.event.data.DelegatedEntry,
                        originAtEnd: Boolean): Tick = {
    val state = new MesoTimingState {
      var mesoRoundingCredit: Double = entry.roundingCredit
      var mesoLastExitTick: Long = entry.lastExitTick
    }
    exitTick(tick, seconds / math.max(1e-9, entry.secondsPerTick), originAtEnd, entry.carryRounding, state)
  }

  /** Seconds a Bus needs for one MESO link: the same density speed as the Car, but a stopped link costs its length. */
  def busTravelSeconds(length: Double, capacity: Double, numberOfCars: Long, freeSpeed: Double, lanes: Int): Double = {
    val speed = model.mobility.util.SpeedUtil.linkEntryDensitySpeed(
      length = length, capacity = capacity, numberOfCars = numberOfCars, freeSpeed = freeSpeed, lanes = lanes)
    if (speed > 0.0) length / (speed / 3.6) else length
  }

  /** The exit tick the Bus would compute itself: truncated without carried rounding, which counts the time in ticks of one second. */
  def busDelegatedExitTick(tick: Tick, seconds: Double, entry: model.mobility.entity.event.data.DelegatedEntry): Tick =
    if (entry.carryRounding) {
      val state = new MesoTimingState {
        var mesoRoundingCredit: Double = entry.roundingCredit
        var mesoLastExitTick: Long = entry.lastExitTick
      }
      exitTick(tick, seconds, originAtEnd = false, carry = true, state)
    } else tick + seconds.toLong

  def exitTick(tick: Tick, timeTicks: Double, originAtEnd: Boolean, carry: Boolean, journey: MesoTimingState): Tick =
    if (!carry || originAtEnd) {
      journey.mesoRoundingCredit = 0.0
      journey.mesoLastExitTick = -1L
      tick + math.ceil(timeTicks).toLong
    } else {
      val waited = if (journey.mesoLastExitTick < 0) Long.MaxValue else math.max(0L, tick - journey.mesoLastExitTick)
      val credit = if (waited == Long.MaxValue) 0.0 else math.max(0.0, journey.mesoRoundingCredit - waited.toDouble)
      val trueExit = tick - credit + timeTicks
      val exit = math.max(tick + 1L, math.ceil(trueExit - 1e-9).toLong)
      journey.mesoRoundingCredit = exit - trueExit
      journey.mesoLastExitTick = exit
      exit
    }
}
