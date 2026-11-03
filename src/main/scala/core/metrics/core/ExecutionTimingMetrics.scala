package org.interscity.htc
package core.metrics.core

import io.prometheus.client.{Collector, CollectorRegistry, Gauge, Histogram}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/** Host performance telemetry is deliberately outside rollback/checkpoint state. */
enum TimedOperation(val label: String) {
  case ConfigLoad extends TimedOperation("config_load")
  case SimulationLoad extends TimedOperation("simulation_load")
  case ProgressiveIndex extends TimedOperation("progressive_index")
  case ProgressiveLoad extends TimedOperation("progressive_load")
  case SimulationLoop extends TimedOperation("simulation_loop")
  case TimeManagerSynchronization extends TimedOperation("time_manager_synchronization")
  case LoadBalance extends TimedOperation("load_balance")
  case RebalanceEvaluation extends TimedOperation("rebalance_evaluation")
  case Rebalance extends TimedOperation("rebalance")
  case RouteVehicle extends TimedOperation("route_vehicle")
  case RouteWalking extends TimedOperation("route_walking")
}

final case class TimingSnapshot(operation: String, count: Long, failed: Long, totalSeconds: Double,
  maxSeconds: Double, lastSeconds: Double, active: Int, activeSeconds: Double) {
  def elapsedSeconds: Double = totalSeconds + activeSeconds
  def reportData: Map[String, Any] = Map(
    "operation" -> operation, "count" -> count, "failed_count" -> failed,
    "total_seconds" -> totalSeconds, "max_seconds" -> maxSeconds, "last_seconds" -> lastSeconds,
    "mean_seconds" -> (if (count == 0) 0.0 else totalSeconds / count),
    "active_count" -> active, "active_seconds" -> activeSeconds, "elapsed_seconds" -> elapsedSeconds,
    "scope" -> "process_cumulative", "clock" -> "monotonic", "unit" -> "seconds"
  )
}

/** Bounded per-operation state; active spans use their start-time sum, not a map per route. */
final class ExecutionTimers(clock: () => Long = () => System.nanoTime(),
  observed: (TimedOperation, Double, Boolean) => Unit = (_, _, _) => ()) {
  private val origin = clock()
  private def nowNanos: Long = clock() - origin
  private final class State {
    var count = 0L; var failed = 0L; var total = 0.0; var max = 0.0; var last = 0.0
    var active = 0; var activeStarts = 0.0
  }
  private val states = TimedOperation.values.map(op => op -> new State).toMap

  final class Span private[ExecutionTimers](operation: TimedOperation, started: Long) {
    private val finished = new AtomicBoolean(false)
    def stop(failed: Boolean = false): Unit = if (finished.compareAndSet(false, true)) {
      val elapsed = math.max(0L, nowNanos - started) / 1e9
      ExecutionTimers.this.synchronized {
        val state = states(operation)
        state.active -= 1; state.activeStarts -= started
        state.count += 1; if (failed) state.failed += 1
        state.total += elapsed; state.last = elapsed; state.max = math.max(state.max, elapsed)
        observed(operation, elapsed, failed)
      }
    }
  }
  def start(operation: TimedOperation): Span = synchronized {
    val now = nowNanos
    val state = states(operation)
    state.active += 1; state.activeStarts += now
    new Span(operation, now)
  }
  def measure[A](operation: TimedOperation)(body: => A): A = {
    val span = start(operation)
    var failed = true
    try { val result = body; failed = false; result }
    finally span.stop(failed)
  }
  def snapshots: Vector[TimingSnapshot] = synchronized {
    val now = nowNanos
    TimedOperation.values.toVector.flatMap { op =>
      val state = states(op)
      Option.when(state.count > 0 || state.active > 0) {
        val active = math.max(0.0, now.toDouble * state.active - state.activeStarts) / 1e9
        TimingSnapshot(op.label, state.count, state.failed, state.total, state.max, state.last, state.active, active)
      }
    }
  }
}

object ExecutionTimingMetrics {
  val duration: Histogram = Histogram.build().name("htc_execution_duration_seconds")
    .help("Completed host operation durations, including cached/no-route requests and failed attempts")
    .labelNames("operation").buckets(0.00001, 0.0001, 0.001, 0.01, 0.1, 1, 10, 60, 600).register()
  val maximum: Gauge = Gauge.build().name("htc_execution_duration_max_seconds")
    .help("Maximum completed operation duration since process startup").labelNames("operation").register()
  val last: Gauge = Gauge.build().name("htc_execution_duration_last_seconds")
    .help("Most recent completed host operation duration").labelNames("operation").register()
  val failures: io.prometheus.client.Counter = io.prometheus.client.Counter.build()
    .name("htc_execution_failed_total").help("Host operation attempts ending with an exception or cancellation")
    .labelNames("operation").register()
  val timers = new ExecutionTimers(observed = (op, seconds, failed) => {
    duration.labels(op.label).observe(seconds)
    last.labels(op.label).set(seconds)
    maximum.labels(op.label).set(math.max(maximum.labels(op.label).get(), seconds))
    if (failed) failures.labels(op.label).inc()
  })
  private val live: Collector = new Collector {
    override def collect(): java.util.List[Collector.MetricFamilySamples] = {
      val elapsed = new io.prometheus.client.GaugeMetricFamily("htc_execution_elapsed_seconds",
        "Completed duration plus elapsed active spans; overlapping work is additive, not CPU time", List("operation").asJava)
      val active = new io.prometheus.client.GaugeMetricFamily("htc_execution_active",
        "Number of active host operation stopwatches", List("operation").asJava)
      timers.snapshots.foreach { row =>
        elapsed.addMetric(List(row.operation).asJava, row.elapsedSeconds)
        active.addMetric(List(row.operation).asJava, row.active.toDouble)
      }
      List[Collector.MetricFamilySamples](elapsed, active).asJava
    }
  }.register[Collector]()
}
