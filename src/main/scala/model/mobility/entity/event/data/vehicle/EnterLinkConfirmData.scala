package org.interscity.htc
package model.mobility.entity.event.data.vehicle

import core.entity.event.data.BaseEventData
import core.types.Tick
import model.mobility.entity.event.data.signal.SignalStatePredictionData

/** Event-driven: Link confirms entry with calculated travel time
  *
  * Provides all information vehicle needs to schedule arrival:
  * - Base travel time (from density model)
  * - Signal state prediction (if signal exists)
  * - Destination node
  *
  * Vehicle can now schedule exact arrival time without polling.
  *
  * @param linkId           Link being entered
  * @param entryTick        Actual entry tick
  * @param baseTravelTime   Travel time in ticks (from density calculation)
  * @param destinationNode  Target node
  * @param signalState      Optional signal prediction
  */
case class EnterLinkConfirmData(
  linkId: String,
  entryTick: Tick,
  baseTravelTime: Tick,
  destinationNode: String,
  signalState: Option[SignalStatePredictionData]
) extends BaseEventData
