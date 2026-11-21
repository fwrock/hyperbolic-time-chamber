package org.interscity.htc
package model.mobility.entity.event.data

import core.runtime.DelegatedWake

/** What a vehicle hands the Link so the Link can schedule the vehicle's wake at the end of the traversal.
  * The timing fields mirror the vehicle's own rounding state, which the Link needs to reproduce the exit tick exactly.
  */
final case class DelegatedEntry(
  wake: DelegatedWake,
  secondsPerTick: Double,
  carryRounding: Boolean,
  roundingCredit: Double,
  lastExitTick: Long
)
