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

  /** Records the start of a simulation phase.
    *
    * @param phase
    *   Phase name: "config_load", "loading", "progressive_loading", or "simulation"
    */
  def recordPhaseStart(phase: String): Unit = {
    val nowSeconds = System.currentTimeMillis().toDouble / 1000.0
    phaseStartTimestamp.labels(phase).set(nowSeconds)
    logger.info(s"[Phase] '$phase' started")
  }

  /** Records the end of a simulation phase and sets its duration gauge.
    *
    * @param phase
    *   Phase name matching a prior [[recordPhaseStart]] call
    */
  def recordPhaseEnd(phase: String): Unit = {
    val nowSeconds = System.currentTimeMillis().toDouble / 1000.0
    val startSeconds = phaseStartTimestamp.labels(phase).get()
    if (startSeconds > 0) {
      val duration = nowSeconds - startSeconds
      phaseDurationSeconds.labels(phase).set(duration)
      logger.info(f"[Phase] '$phase' completed in $duration%.2f seconds")
    }
  }
}
