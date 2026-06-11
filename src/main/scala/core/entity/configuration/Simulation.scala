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
  enableDynamicModeChoice: Boolean = false,
  /** When set, overrides `modeChoiceWeights.includedModes` for every Person in the simulation.
    * Accepts any subset of: "car", "bicycle", "motorcycle", "bus", "subway", "walk".
    * If absent, each Person uses its own `modeChoiceWeights.includedModes` (or the class default).
    */
  modeChoiceIncludedModes: Option[List[String]] = None,
  randomSeed: Option[Long] = None,
  cityMapFile: Option[String] = None,
  transitMapFile: Option[String] = None,
  transitRoutesFile: Option[String] = None,
  actorsDataSources: List[ActorDataSource] = List.empty,
  /** Optional list of short class names (e.g. "hybrid.actor.BusStop") whose actors should
    * automatically participate in the post-load registration phase, without needing the flag in
    * their individual data files. Useful when the scenario was generated before the flag was
    * introduced and regenerating would take too long.
    */
  postLoadRegistrationClasses: List[String] = List.empty,
  /** Optional list of short actor class names (e.g. "hybrid.actor.Bus", "hybrid.actor.Subway")
    * whose shard regions must be pre-registered at startup on every cluster node even though no
    * initial instances appear in actorsDataSources. Required for any actor type that is created
    * dynamically at runtime (e.g. Bus spawned by BusStation, Subway spawned by SubwayStation):
    * Pekko Cluster Sharding requires the shard region to exist on ALL nodes before any message
    * can be routed to it.
    */
  dynamicActorTypes: List[String] = List.empty,
  /** Optional list of warm-up targets to invoke before simulation ticks start.
    * Format: "fully.qualified.ObjectName#methodName" (methodName defaults to "warmUp").
    * Each target runs on the io-dispatcher before [[StartSimulationTimeEvent]] is sent.
    * Merged with targets from application.conf [[htc.warmup.targets]].
    * Example: "org.interscity.htc.model.hybrid.util.CityMapUtil#warmUp"
    */
  warmupTargets: List[String] = List.empty,
  /** Plugin configuration map — keys are model names (e.g. "emission", "carFollowing"),
    * values are free-form string params consumed by each model's factory.
    * Example: {"emission": {"model": "vt_micro", "co2_per_ml": "2.35"}}
    */
  modelPlugins: Map[String, Map[String, String]] = Map.empty
)
