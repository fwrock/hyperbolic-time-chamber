package org.interscity.htc
package model.hybrid.entity.state.plan

import core.types.Tick

/** Resolves the tick an [[Activity]] actually ends on, given its [[EndTimeSpec]] and the tick the
  * person arrived at the activity. Deliberately stateless: no accumulated offset, no ratchet — the
  * same `(spec, arrivalTick)` pair always resolves to the same departure tick.
  */
trait LatenessPolicy {
  def resolveDepartureTick(spec: EndTimeSpec, arrivalTick: Tick): Tick
}

/** Enforces a minimum one-tick dwell at the activity and never lets a late arrival "catch up" by
  * departing before or exactly when it arrived.
  *
  *   - [[AtTick]]: departs at the literal scheduled tick when arrival was on time
  *     (`arrivalTick < tick`); when arrival is already at or past the scheduled tick, departs one
  *     tick after arrival instead of instantly or in the past.
  *   - [[Duration]]: always `arrivalTick + ticks`, anchored to the real arrival — a late arrival
  *     shifts the departure forward by the same amount, it never silently accumulates elsewhere.
  */
object MinimumDwellLatenessPolicy extends LatenessPolicy {

  def resolveDepartureTick(spec: EndTimeSpec, arrivalTick: Tick): Tick = spec match {
    case AtTick(tick)    => math.max(arrivalTick + 1, tick)
    case Duration(ticks) => arrivalTick + ticks
  }
}
