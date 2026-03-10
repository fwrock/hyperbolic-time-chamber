package org.interscity.htc
package core.entity.configuration

import core.types.Tick

import java.time.LocalDateTime

case class Simulation(
  name: String,
  description: String,
  id: Option[String] = None,
  startTick: Tick,
  endTick: Option[Tick] = None,
  startRealTime: LocalDateTime,
  timeUnit: String,
  timeStep: Long,
  tickDuration: Option[Double] = None,
  duration: Tick,
  extendSimulationIfPendingEventsAfterEnd: Boolean = false,
  randomSeed: Option[Long] = None,
  cityMapFile: Option[String] = None,
  actorsDataSources: List[ActorDataSource] = List.empty
)
