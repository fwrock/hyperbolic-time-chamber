package org.interscity.htc
package core.util

import core.entity.configuration.Simulation
import java.time.LocalDateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalScenarioSettingsSpec extends AnyFlatSpec with Matchers {
  private val scenario = Simulation(name = "example", description = "", startTick = 0L,
    startRealTime = LocalDateTime.of(2025, 1, 1, 0, 0), timeUnit = "seconds", timeStep = 1L,
    duration = 2200L)

  "per-node scenario settings" should "include every resource needed before remote shard initialization" in {
    SimulationUtil.localScenarioSettings(scenario.copy(cityMapFile = Some("city.json"),
      transitMapFile = Some("stops.json"), transitRoutesFile = Some("routes.json"))) shouldBe Map(
      "htc.simulation.duration" -> "2200",
      SimulationClock.Setting -> "1.0",
      "htc.mobility.city-map-file" -> "city.json",
      "htc.mobility.transit-map-file" -> "stops.json",
      "htc.mobility.transit-routes-file" -> "routes.json")
  }

  it should "leave absent resource overrides to the configured environment" in {
    SimulationUtil.localScenarioSettings(scenario) shouldBe Map("htc.simulation.duration" -> "2200",
      SimulationClock.Setting -> "1.0")
  }
}
