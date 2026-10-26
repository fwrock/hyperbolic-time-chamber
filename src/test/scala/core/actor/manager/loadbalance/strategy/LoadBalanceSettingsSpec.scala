package org.interscity.htc
package core.actor.manager.loadbalance.strategy

import com.typesafe.config.ConfigFactory
import core.enumeration.LoadBalanceStrategyEnum
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LoadBalanceSettingsSpec extends AnyFlatSpec with Matchers {
  private def settings(overrides: String = "") = LoadBalanceSettings.read(
    ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  "load balancing startup settings" should "accept the shipped configuration and explicit supported strategies" in {
    settings().strategyConfig.initialSpatialDepth shouldBe 2
    for ((name, kind) <- Seq("hybrid" -> LoadBalanceStrategyEnum.Hybrid,
      "geo-affinity" -> LoadBalanceStrategyEnum.GeoAffinity, "type-aware" -> LoadBalanceStrategyEnum.TypeAware,
      "default" -> LoadBalanceStrategyEnum.Default, "disabled" -> LoadBalanceStrategyEnum.Disabled))
      settings(s"htc.load-balance-manager.strategy=$name").strategyType shouldBe kind
  }

  it should "reject invalid strategy parameters instead of silently replacing them" in {
    for (value <- Seq("strategy=unknown", "quadtree.initial-depth=99", "quadtree.max-actors-per-shard=0",
      "migration.max-concurrent=0", "geo-affinity.weight=1.5", "metrics.interval-seconds=0"))
      intercept[IllegalArgumentException](settings(s"htc.load-balance-manager.$value"))
  }

  it should "reject unusable geographic extents before regions are created" in {
    intercept[IllegalArgumentException](settings("""
      htc.load-balance-manager.world-bounds { min-x=10, max-x=5, min-y=0, max-y=1 }
    """))
  }
}
