package org.interscity.htc
package core.actor.manager.time

import core.api.SimulatorSettingsRegistry
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TimeManagerSchedulingStrategySpec extends AnyFlatSpec with Matchers {
  "automatic scheduling" should "prefer node-local for direct conservative fixed placement" in {
    TimeManagerSchedulingStrategy.Auto.useNodeLocal(true, false) shouldBe true
    SimulatorSettingsRegistry.catalog
      .find(_.configPath == "htc.time-manager.scheduling-strategy")
      .get
      .defaultValue shouldBe "auto"
    ConfigFactory.load().getString("htc.time-manager.scheduling-strategy") shouldBe "auto"
    ConfigFactory.load().getInt("htc.time-manager.node-local.nodes") shouldBe 0
  }

  it should "retain the existing pool for other transports, Time Warp and live rebalancing" in {
    TimeManagerSchedulingStrategy.Auto.useNodeLocal(false, false) shouldBe false
    TimeManagerSchedulingStrategy.Auto.useNodeLocal(true, true) shouldBe false
    TimeManagerSchedulingStrategy.ClusterPool.useNodeLocal(true, false) shouldBe false
  }

  it should "reject explicitly incompatible node-local selections" in {
    intercept[IllegalArgumentException](
      TimeManagerSchedulingStrategy.NodeLocalTick.useNodeLocal(false, false)
    )
    intercept[IllegalArgumentException](
      TimeManagerSchedulingStrategy.NodeLocalTick.useNodeLocal(true, true)
    )
  }
}
