package org.interscity.htc
package model.hybrid.entity.state

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.enumeration.BusStationStateEnum
import org.interscity.htc.model.hybrid.entity.state.enumeration.BusStationStateEnum.Start
import org.interscity.htc.model.hybrid.entity.state.model.{ BusInformation, RoutePathItem, SubRoutePair }

import scala.collection.mutable

case class BusStationState(
  startTick: Long,
  name: String,
  origin: String,
  destination: String = null,
  busStops: mutable.LinkedHashMap[String, String], // Preserves insertion order
  interval: Tick,
  buses: mutable.Queue[BusInformation],
  goingRoute: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]] = Some(
    mutable.Map.empty
  ),
  goingBestCost: Double = Double.MaxValue,
  returningRoute: Option[mutable.Map[SubRoutePair, mutable.Queue[(Identify, Identify)]]] = Some(
    mutable.Map.empty
  ),
  returningBestCost: Double = Double.MaxValue,
  var status: BusStationStateEnum = Start
) extends BaseState(startTick = startTick)
