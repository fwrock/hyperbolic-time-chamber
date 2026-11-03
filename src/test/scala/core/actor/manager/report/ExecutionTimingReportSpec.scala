package org.interscity.htc
package core.actor.manager.report

import core.metrics.ExecutionTimingCollector
import core.metrics.core.TimingSnapshot
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ExecutionTimingReportSpec extends AnyFlatSpec with Matchers {
  "Timing reports" should "retain the source node, process identity, units and active spans" in {
    val snapshot = ExecutionTimingCollector.Captured("worker-process", 3,
      Vector(TimingSnapshot("route_walking", 4L, 1L, 2.0, 1.0, 0.5, 1, 0.25)))
    val event = ReportManager.timingReports(snapshot).head
    event.node shouldBe 3
    event.entityId should include ("worker-process:route_walking")
    event.label shouldBe "execution_timing_summary"
    val data = event.data.asInstanceOf[Map[String, Any]]
    data("mean_seconds") shouldBe 0.5
    data("elapsed_seconds") shouldBe 2.25
    data("scope") shouldBe "process_cumulative"
    data("unit") shouldBe "seconds"
    data("process_id") shouldBe "worker-process"
  }
}
