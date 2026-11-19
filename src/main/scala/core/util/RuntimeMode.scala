package org.interscity.htc
package core.util

import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.interscity.htc.core.api.SimulatorSettingsRegistry

enum RuntimeMode {
  case Cluster, SingleNode
}

object RuntimeMode {
  val ConfigPath = "htc.runtime.mode"

  private val modes = new java.util.concurrent.ConcurrentHashMap[ActorSystem, RuntimeMode]()

  /** The mode this actor system runs in, fixed on first use: later changes to the settings
    * registry (for example through the REST API) must not make some components switch mid-run.
    */
  def forSystem(system: ActorSystem): RuntimeMode =
    modes.computeIfAbsent(system, s => from(s.settings.config))

  /** Pins the mode decided at bootstrap, before any component asks. */
  def fix(system: ActorSystem, mode: RuntimeMode): Unit = modes.put(system, mode)

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
