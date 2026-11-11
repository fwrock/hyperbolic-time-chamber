package org.interscity.htc.model.hybrid.collections

import org.interscity.htc.core.cache.CalculationCache
import org.interscity.htc.core.util.{SimulationUtil, JsonUtil}
import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.model.hybrid.util.CityMapUtil
import java.nio.file.Path

/** Invoked in independent JVMs by the release verification script. */
object LandmarkCacheJvmProbe {
  def main(args: Array[String]): Unit = {
    val config = JsonUtil.fromJson[Simulation](java.nio.file.Files.readString(Path.of(args(0))))
    SimulationUtil.installLocalScenarioSettings(config)
    require(CalculationCache.configured == config.calculationCache)
    CityMapUtil.warmUp()
    val vehicle = CityMapUtil.compactAltIndex
    val pedestrian = CityMapUtil.compactAltIndexPedestrian
    val n = CityMapUtil.compactGraph.n
    val values = (for (u <- 0 until n; v <- 0 until n) yield
      s"${vehicle.heuristic(u, v)},${pedestrian.heuristic(u, v)}").mkString("|")
    println(s"CACHE-PROBE-RESULT:$values")
  }
}
