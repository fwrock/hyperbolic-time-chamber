package org.interscity.htc
package core.util

import com.typesafe.config.Config
import org.interscity.htc.core.api.SimulatorSettingsRegistry

enum RuntimeMode {
  case Cluster, SingleNode
}

object RuntimeMode {
  val ConfigPath = "htc.runtime.mode"

  def from(config: Config): RuntimeMode =
    SimulatorSettingsRegistry
      .get(ConfigPath)
      .orElse(Option.when(config.hasPath(ConfigPath))(config.getString(ConfigPath)))
      .map(_.trim.toLowerCase)
      .getOrElse("cluster") match {
      case "cluster"     => Cluster
      case "single-node" => SingleNode
      case other =>
        throw new IllegalArgumentException(
          s"Unknown $ConfigPath '$other'; expected 'cluster' or 'single-node'"
        )
    }
}
