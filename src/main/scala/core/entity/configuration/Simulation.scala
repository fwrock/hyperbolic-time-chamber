package org.interscity.htc
package core.entity.configuration

import core.types.Tick

import java.time.LocalDateTime

case class Simulation(
  name: String,
  description: String,
  id: Option[String] = None,
  startTick: Tick,
  startRealTime: LocalDateTime,
  timeUnit: String,
  timeStep: Long,
  duration: Tick,
  randomSeed: Option[Long] = None, // 🆕
  actorsDataSources: List[ActorDataSource]
)
