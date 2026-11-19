package org.interscity.htc
package core.util

import com.typesafe.config.{ Config, ConfigFactory, ConfigObject, ConfigValue, ConfigValueType }
import core.api.SimulatorSettingsRegistry
import core.entity.configuration.Simulation

import scala.jdk.CollectionConverters.*

/** Validates scenario-owned HTC settings without publishing a partial configuration. */
object ScenarioSettings {

  /** Decode JSON types and reject misspellings, nulls, invalid options and clock/path conflicts.
    * Explicit scenario fields own the clock, duration and resource paths when present.
    */
  def read(simulation: Simulation, resources: Map[String, String]): Config = {
    require(simulation.settings != null, "simulation.json settings must be an object, not null")
    val supplied = flatten(ConfigFactory.parseString(JsonUtil.toJson(simulation.settings)).root())
    val base = SimulatorSettingsRegistry.declaredDefaults
    supplied.entrySet().asScala.foreach {
      entry =>
        val key = entry.getKey
        require(
          SimulatorSettingsRegistry.allKeys.contains(key),
          s"Unknown simulation setting '$key'"
        )
        require(
          key != "htc.simulation.config-file",
          "simulation.json cannot select its own config-file"
        )
        val value = entry.getValue
        require(value.valueType() != ConfigValueType.NULL, s"Setting '$key' cannot be null")
        val raw = SimulatorSettingsRegistry.renderValue(supplied, key)
        SimulatorSettingsRegistry
          .validate(key, raw)
          .foreach(
            error => throw new IllegalArgumentException(error)
          )
        if (base.hasPath(key)) {
          val expected = base.getValue(key).valueType()
          val actual = value.valueType()
          // HOCON supports duration/memory strings and env substitutions; validate getters/options
          // above as well, but do not accept a JSON object/list where a scalar is expected.
          require(
            actual == expected || (actual == ConfigValueType.STRING && expected != ConfigValueType.LIST),
            s"Setting '$key' expects $expected, got $actual"
          )
        }
        resources.get(key).foreach {
          canonical =>
            val equal =
              if (key == SimulationClock.Setting || key == "htc.simulation.duration")
                scala.util.Try(raw.toDouble == canonical.toDouble).getOrElse(false)
              else raw == canonical
            require(equal, s"Setting '$key' conflicts with its simulation.json field")
        }
    }
    supplied
  }

  /** Normalize nested and dotted JSON keys, rejecting ambiguous duplicate declarations. */
  private def flatten(root: ConfigObject): Config = {
    var result = ConfigFactory.empty()
    def visit(value: ConfigValue, path: String): Unit = value match {
      case obj: ConfigObject =>
        require(
          path.isEmpty || SimulatorSettingsRegistry.allKeys.exists(_.startsWith(s"$path.")),
          s"Unknown simulation settings group '$path'"
        )
        obj.asScala.foreach {
          case (key, child) =>
            visit(child, if (path.isEmpty) key else s"$path.$key")
        }
      case leaf =>
        require(
          SimulatorSettingsRegistry.allKeys.contains(path),
          s"Unknown simulation setting '$path'"
        )
        require(leaf.valueType() != ConfigValueType.NULL, s"Setting '$path' cannot be null")
        require(!result.hasPath(path), s"Duplicate simulation setting '$path'")
        result = result.withValue(path, leaf)
    }
    visit(root, "")
    result
  }
}
