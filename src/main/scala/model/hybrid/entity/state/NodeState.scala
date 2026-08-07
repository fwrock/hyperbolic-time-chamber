package org.interscity.htc
package model.hybrid.entity.state

import core.entity.state.BaseState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.model.{ PendingLinkAccessRequest, SignalState }

import scala.collection.mutable

case class NodeState(
  startTick: Tick,
  reporterType: ReportTypeEnum = null,
  scheduleOnTimeManager: Boolean = false,
  latitude: Double,
  longitude: Double,
  links: List[String],
  connections: mutable.Map[String, Identify] = mutable.Map.empty,
  approachConnections: mutable.Map[String, Identify] = mutable.Map.empty,
  signals: mutable.Map[String, SignalState] = mutable.Map.empty,
  busStops: mutable.Map[String, Identify] = mutable.Map.empty,
  subwayStations: mutable.Map[String, Identify] = mutable.Map.empty,
  signalWaitingCounts: mutable.Map[String, Int] = mutable.Map.empty,
  capacityWaitQueue: mutable.Map[String, mutable.Queue[PendingLinkAccessRequest]] = mutable.Map.empty,
  availableCapacity: mutable.Map[String, Int] = mutable.Map.empty
) extends BaseState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager
    )
