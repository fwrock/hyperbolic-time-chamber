package org.interscity.htc
package core.entity.configuration

import core.types.CoreTypes.Tick

import org.htc.protobuf.core.entity.actor.ActorDataSource

import java.time.LocalDateTime

case class Simulation(
  name: String,
  description: String,
  startTick: Tick,
  startRealTime: LocalDateTime,
  timeUnit: String,
  timeStep: Long,
  duration: Tick,
  actorsDataSources: List[ActorDataSource]
)
