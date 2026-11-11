package org.interscity.htc
package core.api

import core.metrics.core.SimulationMetrics
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SimulationControllerStatusSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val system = ActorSystem("SimulationControllerStatusSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { SimulationController.initialize(system, true); system.terminate(); () }

  "runtime status" should "be Running at tick zero once the coordinator starts" in {
    SimulationMetrics.currentTick.set(0.0)
    SimulationController.initialize(system, true)
    SimulationController.markLoading()
    SimulationController.status shouldBe SimulationStatus.Loading
    SimulationController.markRunning()
    SimulationController.status shouldBe SimulationStatus.Running
    SimulationController.markFailed()
    SimulationController.markRunning()
    SimulationController.status shouldBe SimulationStatus.Failed
    SimulationController.initialize(system, true)
    SimulationController.status shouldBe SimulationStatus.Idle
  }
}
