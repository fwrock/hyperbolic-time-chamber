package org.interscity.htc
package core.util

import core.api.SimulatorSettingsRegistry
import core.entity.configuration.Simulation
import core.types.Tick

final case class SimulationClock(secondsPerTick: Double = 1.0) {
  require(secondsPerTick.isFinite && secondsPerTick > 0.0 && secondsPerTick <= 1.0,
    "tickDuration must be finite seconds in (0, 1]")
  require(math.abs(1.0 / secondsPerTick - math.rint(1.0 / secondsPerTick)) < 1e-8,
    "tickDuration must divide one second into an integer number of ticks")

  def seconds(ticks: Tick): Double = ticks.toDouble * secondsPerTick
  def ticksCeil(seconds: Double): Tick = {
    require(seconds.isFinite && seconds >= 0.0 && seconds / secondsPerTick < Long.MaxValue.toDouble)
    math.ceil(seconds / secondsPerTick - 1e-9).toLong
  }
}

object SimulationClock {
  val Setting = "htc.simulation.tick-duration-seconds"

  def current: SimulationClock = SimulationClock(
    SimulatorSettingsRegistry.get(Setting).map(_.toDouble).getOrElse(1.0))

  def validateScenario(s: Simulation): SimulationClock = {
    val clock = SimulationClock(s.tickDuration.getOrElse(1.0))
    if (clock.secondsPerTick != 1.0) {
      val supported = Set("hybrid.actor.Car", "hybrid.actor.Link", "hybrid.actor.Node", "hybrid.actor.TrafficSignal")
      require(s.timeUnit.equalsIgnoreCase("seconds") && s.timeStep == 1L,
        "fractional clocks require timeUnit=seconds and timeStep=1 runtime tick")
      require(s.actorsDataSources.forall(a => supported(StringUtil.getModelClassNameWithoutPackage(a.classType))),
        "fractional clocks currently support only MICRO Car/Link/Node/TrafficSignal sources")
      require(s.dynamicActorTypes.isEmpty && !s.enableDynamicModeChoice && s.modelPlugins.isEmpty,
        "fractional clocks do not yet support model births, mode choice or plugins")
    }
    clock
  }
}
