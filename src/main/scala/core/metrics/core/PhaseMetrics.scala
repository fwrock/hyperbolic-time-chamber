package org.interscity.htc
package core.metrics.core

import io.prometheus.client.Gauge
import org.slf4j.LoggerFactory

/** Simulation phase timing metrics and helpers.
  *
  *   - htc_phase_start_timestamp_seconds — Unix timestamp when each phase started
  *   - htc_phase_duration_seconds — wall-clock duration of each completed phase
  *
  * Phases: config_load, loading, progressive_loading, simulation
  */
object PhaseMetrics {

  private val logger = LoggerFactory.getLogger(getClass)

  val phaseStartTimestamp: Gauge = Gauge
    .build()
    .name("htc_phase_start_timestamp_seconds")
    .help("Unix timestamp (seconds) when each simulation phase started")
    .labelNames("phase")
    .register()

  val phaseDurationSeconds: Gauge = Gauge
    .build()
    .name("htc_phase_duration_seconds")
    .help("Wall-clock duration in seconds for each completed simulation phase")
    .labelNames("phase")
    .register()

  private val starts = scala.collection.mutable.Map.empty[String, (Long, Option[ExecutionTimingMetrics.timers.Span])]

  def recordPhaseStart(phase: String): Unit = synchronized {
    if (!starts.contains(phase)) {
      val operation = phase match {
        case "config_load" => Some(TimedOperation.ConfigLoad)
        case "loading" => Some(TimedOperation.SimulationLoad)
        case _ => None
      }
      starts(phase) = (System.nanoTime(), operation.map(ExecutionTimingMetrics.timers.start))
      phaseStartTimestamp.labels(phase).set(System.currentTimeMillis().toDouble / 1000.0)
      phaseDurationSeconds.labels(phase).set(0.0)
      logger.info(s"[Phase] '$phase' started")
    }
  }

  def recordPhaseEnd(phase: String, failed: Boolean = false): Unit = synchronized {
    starts.remove(phase).foreach { case (started, span) =>
      val duration = math.max(0L, System.nanoTime() - started) / 1e9
      phaseDurationSeconds.labels(phase).set(duration)
      span.foreach(_.stop(failed))
      logger.info(f"[Phase] '$phase' completed in $duration%.2f seconds")
    }
  }
}
