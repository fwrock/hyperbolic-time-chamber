package org.interscity.htc
package model.mobility.entity.state.model

import core.types.Tick
import model.mobility.entity.event.data.{EnterLinkData, LeaveLinkData}

final case class MesoCapacityConfig(flowVehiclesPerHour: Double, storageVehicles: Int) {
  require(flowVehiclesPerHour.isFinite && flowVehiclesPerHour > 0, "MESO flow must be positive vehicles/hour")
  require(storageVehicles > 0, "MESO storage must be positive vehicles")
}

final case class PendingMesoEntry(actorId: Long, shardId: String, requestedTick: Tick,
                                   requestSeq: Long, data: EnterLinkData)
final case class PendingMesoExit(actorId: Long, shardId: String, requestedTick: Tick,
                                  requestSeq: Long, data: LeaveLinkData)

final case class MesoCapacityState(
  var credit: Double = 0.0,
  var creditTick: Option[Tick] = None,
  var entries: List[PendingMesoEntry] = Nil,
  var exits: List[PendingMesoExit] = Nil,
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs = classOf[java.lang.Long])
  var originVehicles: List[Long] = Nil,
  var wakeTick: Option[Tick] = None,
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs = classOf[java.lang.Long])
  var destinationVehicles: List[Long] = Nil
)
