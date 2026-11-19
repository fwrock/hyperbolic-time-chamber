package org.interscity.htc
package core.util

import com.typesafe.config.ConfigFactory
import core.api.SimulatorSettingsRegistry
import core.entity.configuration.Simulation
import java.time.LocalDateTime
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Exercises real JSON decoding and the shared per-JVM configuration installation path. */
class ScenarioSettingsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private val registry = SimulatorSettingsRegistry
  private val scenario = Simulation(
    name = "config",
    description = "",
    startTick = 0L,
    startRealTime = LocalDateTime.of(2025, 1, 1, 0, 0),
    timeUnit = "seconds",
    timeStep = 1L,
    duration = 20L
  )

  override def beforeEach(): Unit = {
    registry.clearAll()
    registry.installScenario(ConfigFactory.empty())
  }
  override def afterEach(): Unit = {
    registry.clearAll()
    registry.installScenario(ConfigFactory.empty())
  }

  private def install(settings: Map[String, Any]): Unit =
    SimulationUtil.installLocalScenarioSettings(scenario.copy(settings = settings))

  "simulation.json settings" should "decode nested and dotted keys with their JSON types" in {
    val json = JsonUtil.toJson(scenario).dropRight(1) +
      """, "settings":{"htc":{"routing":{"cache-strategy":"disabled"}},
        |"htc.report-manager.enabled":false,"htc.warmup.max-workers-per-node":3,
        |"htc.routing.accounted.worker-ids":[17,21]}}""".stripMargin
    SimulationUtil.installLocalScenarioSettings(JsonUtil.fromJson[Simulation](json))
    val effective = registry.effective
    effective.getString("htc.routing.cache-strategy") shouldBe "disabled"
    effective.getBoolean("htc.report-manager.enabled") shouldBe false
    effective.getInt("htc.warmup.max-workers-per-node") shouldBe 3
    effective.getLongList("htc.routing.accounted.worker-ids").size shouldBe 2
    registry.effectiveSource("htc.routing.cache-strategy") shouldBe "simulation_json"
  }

  it should "preserve defaults and old scenario JSON without a settings block" in {
    val old = JsonUtil.toJson(scenario).replace(",\"settings\":{}", "")
    JsonUtil.fromJson[Simulation](old).settings shouldBe Map.empty
    install(Map.empty)
    registry.effective.getString("htc.time-manager.conservative-transport") shouldBe
      ConfigFactory.load().getString("htc.time-manager.conservative-transport")
    registry.get("htc.simulation.duration") shouldBe Some("20")
  }

  it should "apply API over scenario over a supplied deployment configuration" in {
    val base = ConfigFactory
      .parseString("htc.warmup.max-workers-per-node=8")
      .withFallback(ConfigFactory.load())
    install(Map("htc.warmup.max-workers-per-node" -> 3))
    registry.overlay(base).getInt("htc.warmup.max-workers-per-node") shouldBe 3
    registry.set("htc.warmup.max-workers-per-node", "5")
    registry.getInt("htc.warmup.max-workers-per-node", base) shouldBe 5
    registry.overlay(base).getInt("htc.warmup.max-workers-per-node") shouldBe 5
    registry.clear("htc.warmup.max-workers-per-node")
    registry.overlay(base).getInt("htc.warmup.max-workers-per-node") shouldBe 3
  }

  it should "replace rather than accumulate settings across scenarios and invalidate cached overlays" in {
    val base = ConfigFactory.load()
    install(Map("htc.warmup.max-workers-per-node" -> 3))
    registry.overlay(base).getInt("htc.warmup.max-workers-per-node") shouldBe 3
    install(Map.empty)
    registry.overlay(base).getInt("htc.warmup.max-workers-per-node") shouldBe
      base.getInt("htc.warmup.max-workers-per-node")
  }

  it should "reject bad inputs without changing the installed scenario" in {
    install(Map("htc.warmup.max-workers-per-node" -> 3))
    val invalid = List(
      Map("htc.routing.cache-stratejy" -> "disabled"),
      Map("htc.unknown" -> Map.empty[String, Any]),
      Map("pekko.loglevel" -> "DEBUG"),
      Map("htc.report-manager.enabled" -> "maybe"),
      Map("htc.warmup.max-workers-per-node" -> "three"),
      Map("htc.routing.cache-strategy" -> "bogus"),
      Map("htc.routing.accounted.worker-ids" -> 4),
      Map("htc.report-manager.enabled" -> null),
      Map("htc.simulation.duration" -> 21),
      Map("htc.simulation.config-file" -> "other.json"),
      Map(
        "htc" -> Map("warmup" -> Map("max-workers-per-node" -> 2)),
        "htc.warmup.max-workers-per-node" -> 3
      )
    )
    invalid.foreach {
      settings =>
        an[IllegalArgumentException] should be thrownBy install(settings)
        registry.effective.getInt("htc.warmup.max-workers-per-node") shouldBe 3
    }
  }

  it should "allow settings previously backed only by reader defaults" in {
    install(
      Map(
        "htc.progressive-load.index-progress-log-step-pct" -> 5,
        "htc.time-manager.local-tick-progress-log-interval" -> 1000,
        "htc.modifications.files" -> List("changes.json")
      )
    )
    registry.effective.getInt("htc.progressive-load.index-progress-log-step-pct") shouldBe 5
    registry.effective.getLong("htc.time-manager.local-tick-progress-log-interval") shouldBe 1000L
    registry.effective.getStringList("htc.modifications.files").get(0) shouldBe "changes.json"
  }

  it should "export typed settings while redacting secrets" in {
    install(
      Map(
        "htc.report-manager.clickhouse.password" -> "not-for-export",
        "htc.routing.accounted.worker-ids" -> List(17, 21)
      )
    )
    val json = JsonUtil.toJson(ScenarioSettingsExport.manifest(registry.effective))
    json should not include "not-for-export"
    json should include("<redacted>")
    json should include("[17,21]")
  }

  it should "reject conflicting resource paths and permit equivalent clock values" in {
    an[IllegalArgumentException] should be thrownBy SimulationUtil.installLocalScenarioSettings(
      scenario.copy(
        cityMapFile = Some("a.json"),
        settings = Map("htc.mobility.city-map-file" -> "b.json")
      )
    )
    install(Map("htc.simulation.tick-duration-seconds" -> 1, "htc.simulation.duration" -> 20))
    registry.effective.getDouble("htc.simulation.tick-duration-seconds") shouldBe 1.0
  }
}
