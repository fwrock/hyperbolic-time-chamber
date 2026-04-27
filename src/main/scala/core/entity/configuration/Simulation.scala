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
  actorsDataSources: List[ActorDataSource] = List.empty,
  /** Optional list of short class names (e.g. "hybrid.actor.BusStop") whose actors should
    * automatically participate in the post-load registration phase, without needing the flag in
    * their individual data files. Useful when the scenario was generated before the flag was
    * introduced and regenerating would take too long.
    */
  postLoadRegistrationClasses: List[String] = List.empty
)
