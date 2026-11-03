package org.interscity.htc
package core.metrics

import org.interscity.htc.core.metrics.core.{ExecutionTimers, TimedOperation}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ExecutionTimersSpec extends AnyFlatSpec with Matchers {
  "Execution stopwatches" should "include live elapsed time and finish a span only once" in {
    var clock = 100L
    val timers = new ExecutionTimers(() => clock)
    val span = timers.start(TimedOperation.SimulationLoop)
    clock += 2_000_000_000L
    timers.snapshots.head.activeSeconds shouldBe 2.0
    timers.snapshots.head.count shouldBe 0L
    span.stop(); span.stop(failed = true)
    val row = timers.snapshots.head
    row.count shouldBe 1L
    row.failed shouldBe 0L
    row.totalSeconds shouldBe 2.0
    row.active shouldBe 0
  }

  it should "count overlapping spans without pretending their sum is CPU or end-to-end time" in {
    var clock = 0L
    val timers = new ExecutionTimers(() => clock)
    val first = timers.start(TimedOperation.RouteVehicle)
    clock = 1_000_000_000L
    val second = timers.start(TimedOperation.RouteVehicle)
    clock = 3_000_000_000L
    timers.snapshots.head.activeSeconds shouldBe 5.0
    first.stop()
    clock = 4_000_000_000L
    second.stop()
    val row = timers.snapshots.head
    row.totalSeconds shouldBe 6.0
    row.maxSeconds shouldBe 3.0
    row.count shouldBe 2L
    row.active shouldBe 0
  }

  it should "close failed operations while preserving results and exceptions" in {
    var clock = 0L
    val timers = new ExecutionTimers(() => clock)
    timers.measure(TimedOperation.RouteWalking) { clock += 100L; None } shouldBe None
    val failure = new IllegalStateException("route error")
    val caught = intercept[IllegalStateException] {
      timers.measure(TimedOperation.RouteWalking) { clock += 200L; throw failure }
    }
    caught should be theSameInstanceAs failure
    val row = timers.snapshots.head
    row.count shouldBe 2L
    row.failed shouldBe 1L
    row.active shouldBe 0
    row.totalSeconds shouldBe (0.0000003 +- 1e-15)
  }
}
