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
  signals: mutable.Map[String, SignalState] = mutable.Map.empty,
  busStops: mutable.Map[String, Identify] = mutable.Map.empty,
  subwayStations: mutable.Map[String, Identify] = mutable.Map.empty,
  signalWaitingCounts: mutable.Map[String, Int] = mutable.Map.empty,
  // FIFO of vehicles buffered per target link, waiting for downstream capacity to free up.
  // See docs/CONGESTION_PROPAGATION_DESIGN.md.
  capacityWaitQueue: mutable.Map[String, mutable.Queue[PendingLinkAccessRequest]] = mutable.Map.empty,
  // Node's own authoritative count of grants it may still hand out per link right now. Seeded
  // once from the link's reported capacity (RegisterLinkCapacityData at Link init), decremented
  // on every grant Node issues (fresh or buffer-drain alike), incremented by the link's
  // LinkCapacityFreedData reports. Never a raw pass-through of the link's last-reported count —
  // see docs/CONGESTION_PROPAGATION_DESIGN.md's "how many to wake" decision.
  availableCapacity: mutable.Map[String, Int] = mutable.Map.empty
) extends BaseState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager
    )
