package org.interscity.htc
package model.hybrid.support.link

import core.util.JsonUtil
import model.hybrid.entity.state.LinkState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LinkMetricsRollbackSpec extends AnyFlatSpec with Matchers {
  "MICRO summaries" should "retain identical physical averages for every registration order" in {
    import model.hybrid.entity.state.enumeration.SimulationModeEnum
    import model.hybrid.entity.state.model.VehicleInLane
    import scala.collection.mutable
    val state = LinkState(0, from = 1, to = 2, length = 200, lanes = 1,
      speedLimit = 36, freeSpeed = 36, capacity = 100, simulationMode = SimulationModeEnum.MICRO,
      microTicksPerGlobalTick = 1,
      vehiclesByLane = Map(0 -> mutable.Queue.from((1L to 4L).map(id => VehicleInLane(id, "cars", id * 10, 10, 0, 4.5)))))
    val entries = Map(1L -> 201L, 2L -> 119L, 3L -> 311L, 4L -> 352L)
    val waits = Map(1L -> 0.1, 2L -> 0.2, 3L -> 1e-15, 4L -> 1.7)
    val summaries = (1L to 4L).toVector.permutations.map { order =>
      var result = Map.empty[String, Any]
      val reporter = new LinkMetricsReporter((data, _) => result = data, () => "7", () => 354L,
        60, 10, () => state, id => entries.get(id), id => waits(id), () => order, _ => ())
      reporter.emitSumoSummaryStep(354)
      result
    }.toVector
    summaries.distinct.size shouldBe 1
    summaries.head("meanSpeedRelative") shouldBe 1.0
    summaries.head("time") shouldBe 35.4
  }

  "Link reporting state" should "restore cumulative and current-tick counts before replaying undone entries and exits" in {
    var state = LinkState(0L, from = 1L, to = 2L, length = 200.0, lanes = 1,
      speedLimit = 10.0, capacity = 100.0, freeSpeed = 10.0)
    var reports = Vector.empty[Map[String, Any]]
    val reporter = new LinkMetricsReporter((data, _) => reports :+= data, () => "7", () => 10L,
      60, 10, () => state, _ => None, _ => 0.0, () => Iterable.empty, _ => ())
    reporter.ensureSummaryTick(10L)
    reporter.onVehicleLoaded(true)
    val checkpoint = JsonUtil.toJson(state)
    reporter.onVehicleLoaded(true)
    reporter.onVehicleArrived(true, 2.0)
    reporter.emitSumoSummaryStep(10L)
    val expected = reports.last
    reporter.ensureSummaryTick(11L)
    reporter.onVehicleLoaded(true)
    reporter.onVehicleArrived(true, 3.0)
    state = JsonUtil.fromJson[LinkState](checkpoint)
    reporter.onVehicleLoaded(true)
    reporter.onVehicleArrived(true, 2.0)
    reporter.emitSumoSummaryStep(10L)
    reports.last shouldBe expected
    reports.last("loaded") shouldBe 2L
    reports.last("ended") shouldBe 1L
    reports.last("inserted") shouldBe 2
    reports.last("arrived") shouldBe 1
    state.metrics.tickTravelTimeSum shouldBe 2.0
  }
}
