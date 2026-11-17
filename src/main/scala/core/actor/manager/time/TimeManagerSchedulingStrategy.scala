package org.interscity.htc
package core.actor.manager.time

import core.api.SimulatorSettingsRegistry
import com.typesafe.config.Config

/** Automatic defaults select node-local direct execution while preserving other runtime policies.
  */
enum TimeManagerSchedulingStrategy {
  case Auto, ClusterPool, NodeLocalTick

  /** Explicit incompatible requests fail; automatic selection retains supported legacy adapters. */
  def useNodeLocal(directConservative: Boolean, liveRebalancing: Boolean): Boolean = this match {
    case Auto        => directConservative && !liveRebalancing
    case ClusterPool => false
    case NodeLocalTick =>
      require(directConservative, "node-local-tick requires direct conservative execution")
      require(!liveRebalancing, "node-local-tick requires live load balancing disabled")
      true
  }
}

object TimeManagerSchedulingStrategy {

  /** Read the startup-only selection through the same scenario/environment overlay as the runtime.
    */
  def fromConfig(config: Config): TimeManagerSchedulingStrategy =
    SimulatorSettingsRegistry.getString("htc.time-manager.scheduling-strategy", config) match {
      case "auto"            => Auto
      case "cluster-pool"    => ClusterPool
      case "node-local-tick" => NodeLocalTick
      case other =>
        throw new IllegalArgumentException(s"unknown time-manager scheduling strategy: $other")
    }
}
