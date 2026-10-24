package org.interscity.htc
package core.util

import core.entity.configuration.{ActorDataSource, Simulation}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.LocalDateTime

class SimulationClockSpec extends AnyFlatSpec with Matchers {
  private val scenario = Simulation("clock", "", startTick = 0,
    startRealTime = LocalDateTime.of(2025, 1, 1, 0, 0), timeUnit = "seconds", timeStep = 1, duration = 1000)

  "A physical simulation clock" should "convert scheduling and reporting units without floating tick ordering" in {
    val clock = SimulationClock(0.1)
    clock.seconds(243) shouldBe 24.3 +- 1e-9
    clock.ticksCeil(2.0) shouldBe 20L
    clock.ticksCeil(2.01) shouldBe 21L
    SimulationUtil.localScenarioSettings(scenario.copy(tickDuration = Some(0.1)))(SimulationClock.Setting) shouldBe "0.1"
  }

  it should "reject invalid quanta and unsupported fractional-clock models before startup" in {
    for (step <- Seq(0.0, -1.0, Double.NaN, 0.3, 2.0))
      intercept[IllegalArgumentException](SimulationClock(step))
    val fine = scenario.copy(tickDuration = Some(0.1))
    val source = ActorDataSource("cars", "hybrid.actor.Car", null, null)
    SimulationClock.validateScenario(fine.copy(actorsDataSources = List(source))).secondsPerTick shouldBe 0.1
    intercept[IllegalArgumentException](SimulationClock.validateScenario(fine.copy(
      actorsDataSources = List(source.copy(classType = "hybrid.actor.Person")))))
    intercept[IllegalArgumentException](SimulationClock.validateScenario(fine.copy(dynamicActorTypes = List("hybrid.actor.Bus"))))
  }

  it should "derive calendar timestamps from physical time without multiplying large ticks into nanoseconds" in {
    val start = LocalDateTime.of(2025, 1, 1, 0, 0)
    SimulationClock(0.1).at(start, 243) shouldBe start.plusSeconds(24).plusNanos(300000000)
    SimulationClock(0.1).at(start, -1) shouldBe start.minusNanos(100000000)
    SimulationClock(1).at(start, 10000000000L) shouldBe start.plusSeconds(10000000000L)
  }
}
