package org.interscity.htc
package model.hybrid.entity.state.model

import core.types.Tick

case class LinkMetricsState(
  var lastCostPublishTick: Tick = 0L,
  var summaryTick: Tick = Long.MinValue,
  var tickLoaded: Int = 0,
  var tickInserted: Int = 0,
  var tickArrived: Int = 0,
  var tickTravelTimeSum: Double = 0.0,
  var tickProcessingDurationMs: Long = 0L,
  var cumulativeLoadedVehicles: Long = 0L,
  var cumulativeLoaded: Long = 0L,
  var cumulativeArrived: Long = 0L,
  var cumulativeDiscarded: Long = 0L
)
