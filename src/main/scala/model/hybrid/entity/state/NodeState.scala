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
  links: List[Long],
  connections: mutable.Map[Long, Identify] = mutable.Map.empty,
  approachConnections: mutable.Map[Long, Identify] = mutable.Map.empty,
  signals: mutable.Map[Long, SignalState] = mutable.Map.empty,
  // Keyed by bus stop *label* (route label), not a node/entity id -- same rationale as
  // subwayStations below.
  busStops: mutable.Map[String, Identify] = mutable.Map.empty,
  // Keyed by subway *line label* (e.g. "Red"), not a node/entity id -- a station registers once
  // per line it serves, mirroring busStops' label-keyed registration. Not an actor-addressing id,
  // so stays String per the governing String-vs-Long rule (deviation from a blanket Long sweep
  // that had mistakenly caught this field alongside the genuinely id-keyed maps above).
  subwayStations: mutable.Map[String, Identify] = mutable.Map.empty,
  signalWaitingCounts: mutable.Map[Long, Int] = mutable.Map.empty,
  capacityWaitQueue: mutable.Map[Long, mutable.Queue[PendingLinkAccessRequest]] = mutable.Map.empty,
  availableCapacity: mutable.Map[Long, Int] = mutable.Map.empty
) extends BaseState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager
    )
