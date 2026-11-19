package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import model.hybrid.entity.state.CarJourneyState

/** Exit tick of a MESO link traversal.
  *
  * Default: the traversal time rounded up to whole ticks for every link, which adds about half a tick per link.
  * With `carry`: the rounded-up remainder (credit) is carried into the next link, so the cumulative exit time follows the continuous time within one tick;
  * a wait at a node (signal or capacity) absorbs the credit. At least one tick per link is kept either way.
  */
object MesoLinkTiming {
  def exitTick(tick: Tick, timeTicks: Double, originAtEnd: Boolean, carry: Boolean, journey: CarJourneyState): Tick =
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
