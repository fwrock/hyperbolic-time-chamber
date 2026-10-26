package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import com.typesafe.config.Config
import core.entity.control.loadbalance.SpatialBounds
import core.enumeration.LoadBalanceStrategyEnum

case class LoadBalanceSettings(strategyName: String, strategyType: LoadBalanceStrategyEnum,
  worldBounds: SpatialBounds, strategyConfig: StrategyConfig)

object LoadBalanceSettings {
  def read(config: Config): LoadBalanceSettings = {
    val strategyName = config.getString("htc.load-balance-manager.strategy")
    val strategyType = strategyName.toLowerCase(java.util.Locale.ROOT) match {
      case "hybrid"       => LoadBalanceStrategyEnum.Hybrid
      case "type-aware"   => LoadBalanceStrategyEnum.TypeAware
      case "geo-affinity" => LoadBalanceStrategyEnum.GeoAffinity
      case "default"      => LoadBalanceStrategyEnum.Default
      case "disabled"     => LoadBalanceStrategyEnum.Disabled
      case other          => throw new IllegalArgumentException(s"Unknown load balancing strategy: $other")
    }

    val worldBounds =
        SpatialBounds(
          minX = config.getDouble("htc.load-balance-manager.world-bounds.min-x"),
          minY = config.getDouble("htc.load-balance-manager.world-bounds.min-y"),
          maxX = config.getDouble("htc.load-balance-manager.world-bounds.max-x"),
          maxY = config.getDouble("htc.load-balance-manager.world-bounds.max-y")
        )

    val strategyConfig =
        StrategyConfig(
          maxDepth = config.getInt("htc.load-balance-manager.quadtree.max-depth"),
          maxEntitiesPerShard =
            config.getInt("htc.load-balance-manager.quadtree.max-actors-per-shard"),
          minEntitiesPerShard =
            config.getInt("htc.load-balance-manager.quadtree.min-actors-per-shard"),
          loadThreshold = config.getDouble("htc.load-balance-manager.kdtree.load-threshold"),
          predictionWindowSeconds =
            config.getDouble("htc.load-balance-manager.prediction.prediction-window-seconds"),
          rebalanceIntervalSeconds =
            config.getDouble("htc.load-balance-manager.rebalance-interval-seconds"),
          enablePrediction = config.getBoolean("htc.load-balance-manager.prediction.enabled"),
          enableTwoToOneBalance =
            config.getBoolean("htc.load-balance-manager.quadtree.enable-two-to-one-balance"),
          maxConcurrentMigrations =
            config.getInt("htc.load-balance-manager.migration.max-concurrent"),
          flowVectorSamples =
            config.getInt("htc.load-balance-manager.prediction.flow-vector-samples"),
          geographicAffinityWeight =
            config.getDouble("htc.load-balance-manager.geo-affinity.weight"),
          initialSpatialDepth = config.getInt("htc.load-balance-manager.quadtree.initial-depth")
        )
    require(Seq(worldBounds.minX, worldBounds.minY, worldBounds.maxX, worldBounds.maxY,
      worldBounds.width, worldBounds.height, worldBounds.centerX, worldBounds.centerY).forall(_.isFinite) &&
      worldBounds.width > 0 && worldBounds.height > 0, "load balancing requires finite world bounds with positive extent")
    val metricsInterval = config.getDouble("htc.load-balance-manager.metrics.interval-seconds")
    require(metricsInterval.isFinite && metricsInterval > 0, "load metrics interval must be finite and positive")
    LoadBalanceSettings(strategyName, strategyType, worldBounds, strategyConfig)
  }
}
