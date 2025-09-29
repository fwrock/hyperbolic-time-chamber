package org.interscity.htc
package core.entity.configuration

import core.types.Tick

import java.time.LocalDateTime

case class Simulation(
  name: String,
  description: String,
  id: Option[String] = None,  // 🆕 ID ÚNICO DA SIMULAÇÃO (opcional)
  startTick: Tick,
  startRealTime: LocalDateTime,
  timeUnit: String,
  timeStep: Long,
  duration: Tick,
  actorsDataSources: List[ActorDataSource]
)
