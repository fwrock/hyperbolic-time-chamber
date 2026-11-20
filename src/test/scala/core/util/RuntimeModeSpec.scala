package org.interscity.htc
package core.util

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RuntimeModeSpec extends AnyFlatSpec with Matchers {
  "RuntimeMode" should "default to cluster with the shipped application.conf" in {
    RuntimeMode.from(ConfigFactory.load()) shouldBe RuntimeMode.Cluster
  }

  it should "read single-node and tolerate case and whitespace" in {
    RuntimeMode.from(ConfigFactory.parseString("htc.runtime.mode = \" Single-Node \"")) shouldBe RuntimeMode.SingleNode
  }

  it should "default to cluster when the key is absent" in {
    RuntimeMode.from(ConfigFactory.empty()) shouldBe RuntimeMode.Cluster
  }

  it should "reject unknown values" in {
    an[IllegalArgumentException] should be thrownBy
      RuntimeMode.from(ConfigFactory.parseString("htc.runtime.mode = local"))
  }

  it should "pin the mode per actor system so later registry changes cannot flip it mid-run" in {
    import org.apache.pekko.actor.ActorSystem
    import org.interscity.htc.core.api.SimulatorSettingsRegistry
    val system = ActorSystem("RuntimeModePin", ConfigFactory.parseString(
      "pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.runtime.mode=single-node").withFallback(ConfigFactory.load()))
    try {
      RuntimeMode.forSystem(system) shouldBe RuntimeMode.SingleNode
      SimulatorSettingsRegistry.set(RuntimeMode.ConfigPath, "cluster")
      try RuntimeMode.forSystem(system) shouldBe RuntimeMode.SingleNode
      finally SimulatorSettingsRegistry.clear(RuntimeMode.ConfigPath)
      RuntimeMode.fix(system, RuntimeMode.Cluster)
      RuntimeMode.forSystem(system) shouldBe RuntimeMode.Cluster
    } finally org.apache.pekko.testkit.TestKit.shutdownActorSystem(system)
  }

  it should "be a known, startup-only setting with closed options" in {
    import org.interscity.htc.core.api.SimulatorSettingsRegistry
    SimulatorSettingsRegistry.catalog.find(_.configPath == "htc.runtime.mode").map(_.defaultValue) shouldBe Some("cluster")
    SimulatorSettingsRegistry.options("htc.runtime.mode") shouldBe List("cluster", "single-node")
    SimulatorSettingsRegistry.isStartupOnly("htc.runtime.mode") shouldBe true
  }
}
