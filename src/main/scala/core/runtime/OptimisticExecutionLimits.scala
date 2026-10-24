package org.interscity.htc
package core.runtime

import core.api.SimulatorSettingsRegistry
import com.typesafe.config.Config

final case class OptimisticExecutionLimits(
  maxAheadTicks: Long = 50L,
  maxUncommittedEvents: Int = 256,
  maxEventsPerTurn: Int = 64,
  fossilCollection: Boolean = true
) {
  require(maxAheadTicks >= 0L, "negative speculation window")
  require(maxUncommittedEvents > 0 && maxEventsPerTurn > 0, "execution budgets must be positive")

  def ceiling(frontier: Long, horizon: Long): Long =
    math.min(horizon, if (frontier > Long.MaxValue - maxAheadTicks) Long.MaxValue else frontier + maxAheadTicks)
}

object OptimisticExecutionLimits {
  def fromConfig(config: Config): OptimisticExecutionLimits = {
    val root = "htc.time-warp."
    OptimisticExecutionLimits(
      SimulatorSettingsRegistry.get(root + "max-ahead-ticks").fold(config.getLong(root + "max-ahead-ticks"))(_.toLong),
      SimulatorSettingsRegistry.getInt(root + "max-uncommitted-events", config),
      SimulatorSettingsRegistry.getInt(root + "max-events-per-turn", config),
      SimulatorSettingsRegistry.getBoolean(root + "fossil-collection-enabled", config))
  }
}
