package org.interscity.htc
package model.hybrid.entity.state

import core.entity.state.BaseState

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.core.types.Tick
import org.interscity.htc.model.hybrid.entity.state.model.{ PendingLinkAccessRequest, SignalState }

import scala.collection.mutable

/** Every non-first-position `Map[Long, _]` constructor parameter below carries
  * `@JsonDeserialize(keyAs = classOf[java.lang.Long])`: on Scala 3, jackson-module-scala only
  * preserves a case class's full Map key/value generics for the *first* constructor parameter,
  * so any later `Map[Long, _]` field silently deserializes its keys as `String` without this
  * annotation (see `BusStationState` for the full explanation). The `String`-keyed `busStops` /
  * `subwayStations` fields below are unaffected and intentionally left unannotated.
  * Counter maps also need Integer content types: the shared mapper otherwise widens their
  * erased numeric values to Long, which fails on the first replayed Int arithmetic operation.
  */
case class NodeState(
  startTick: Tick,
  reporterType: ReportTypeEnum = null,
  scheduleOnTimeManager: Boolean = false,
  latitude: Double,
  longitude: Double,
  links: List[Long],
  @JsonDeserialize(keyAs = classOf[java.lang.Long])
  connections: mutable.Map[Long, Identify] = mutable.Map.empty,
  @JsonDeserialize(keyAs = classOf[java.lang.Long])
  approachConnections: mutable.Map[Long, Identify] = mutable.Map.empty,
  @JsonDeserialize(keyAs = classOf[java.lang.Long])
  signals: mutable.Map[Long, SignalState] = mutable.Map.empty,
  // Keyed by bus stop *label* (route label), not a node/entity id -- same rationale as
  // subwayStations below.
  busStops: mutable.Map[String, Identify] = mutable.Map.empty,
  // Keyed by subway *line label* (e.g. "Red"), not a node/entity id -- a station registers once
  // per line it serves, mirroring busStops' label-keyed registration. Not an actor-addressing id,
  // so stays String per the governing String-vs-Long rule (deviation from a blanket Long sweep
  // that had mistakenly caught this field alongside the genuinely id-keyed maps above).
  subwayStations: mutable.Map[String, Identify] = mutable.Map.empty,
  @JsonDeserialize(keyAs = classOf[java.lang.Long], contentAs = classOf[java.lang.Integer])
  signalWaitingCounts: mutable.Map[Long, Int] = mutable.Map.empty,
  @JsonDeserialize(keyAs = classOf[java.lang.Long])
  capacityWaitQueue: mutable.Map[Long, mutable.Queue[PendingLinkAccessRequest]] = mutable.Map.empty,
  @JsonDeserialize(keyAs = classOf[java.lang.Long], contentAs = classOf[java.lang.Integer])
  availableCapacity: mutable.Map[Long, Int] = mutable.Map.empty,
  junctionMovements: List[model.JunctionMovement] = Nil
) extends BaseState(
      startTick = startTick,
      reporterType = reporterType,
      scheduleOnTimeManager = scheduleOnTimeManager
    ) {
  require(junctionMovements.map(m => (m.incomingLinkId, m.outgoingLinkId)).distinct.size == junctionMovements.size,
    "junction movements must have unique incoming/outgoing road pairs")
  require(junctionMovements.map(_.connectorLinkId).distinct.size == junctionMovements.size,
    "junction connector must belong to exactly one movement")
}
