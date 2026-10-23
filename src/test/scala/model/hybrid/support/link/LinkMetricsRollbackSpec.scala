package org.interscity.htc
package model.hybrid.support.link

import core.util.JsonUtil
import model.hybrid.entity.state.LinkState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LinkMetricsRollbackSpec extends AnyFlatSpec with Matchers {
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
