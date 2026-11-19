package org.interscity.htc
package core.util

import com.typesafe.config.Config
import core.api.SimulatorSettingsRegistry

import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters.*

/** Offline configuration manifest writer; never starts actors or runs a simulation. */
object ScenarioSettingsExport {

  /** Export declared defaults, or validate and export the effective settings of an input scenario.
    * Usage: `defaults output.json` or `effective simulation.json output.json`. Secrets are
    * redacted; the output is a manifest, not a replacement simulation.json.
    */
  def main(args: Array[String]): Unit = {
    val (config, output) = args.toList match {
      case "defaults" :: output :: Nil => (SimulatorSettingsRegistry.declaredDefaults, output)
      case "effective" :: input :: output :: Nil =>
        SimulationUtil.installLocalScenarioSettings(SimulationUtil.loadSimulationConfig(input))
        (SimulatorSettingsRegistry.effective, output)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: defaults output.json | effective simulation.json output.json"
        )
    }
    Files.writeString(Path.of(output), JsonUtil.toJson(manifest(config)))
  }

  /** Return typed leaf values and defaults, excluding credentials from exported artifacts. */
  private[util] def manifest(config: Config): Map[String, Any] = {
    val settings = config
      .getConfig("htc")
      .entrySet()
      .asScala
      .toList
      .sortBy(_.getKey)
      .map {
        entry =>
          val key = s"htc.${entry.getKey}"
          val value =
            if (SimulatorSettingsRegistry.isSecret(key)) "<redacted>"
            else entry.getValue.unwrapped()
          key -> value
      }
      .toMap
    Map("format" -> "htc.settings-manifest.v1", "settings" -> settings)
  }
}
