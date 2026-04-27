package org.interscity.htc
package core.metrics

import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Histogram
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.LoggerFactory

/** Prometheus metrics server for the HTC simulation.
  *
  * Exposes JVM metrics (heap, GC, threads, CPU) and custom simulation metrics on the configured
  * port (default 9001) at /metrics endpoint.
  *
  * Automatically registered collectors:
  *   - JVM memory (heap, non-heap, pools)
  *   - JVM GC (pause time, count per collector)
  *   - JVM threads (count, states, daemon)
  *   - JVM classloading
  *   - Process CPU, open file descriptors, start time
  *
  * Custom simulation metrics — grouped by concern:
  *
  * ── Simulation Progress ──
  *   - htc_simulation_ticks_total — global tick counter
  *   - htc_simulation_current_tick — current global tick gauge
  *   - htc_simulation_progress_ratio — progress toward configured duration [0,1]
  *   - htc_tick_duration_seconds — histogram of global tick processing time
  *
  * ── Actors ──
  *   - htc_actors_registered_total — cumulative actors registered on TMs
  *   - htc_actors_active — gauge of active actors by type
  *   - htc_events_processed_total — spontaneous events processed (by TM)
  *
  * ── Time Manager ──
  *   - htc_tm_scheduled_actors — actors scheduled on this TM at current tick
  *   - htc_tm_running_events — spontaneous events currently in-flight
  *   - htc_tm_waiting_for_progressive — 1 if GTM is blocked waiting for progressive load
  *
  * ── Progressive Loading ──
  *   - htc_progressive_actors_created_total — actors created by progressive loading
  *   - htc_progressive_loaded_up_to_tick — highest tick fully loaded
  *   - htc_progressive_windows_loaded_total — number of tick windows completed
  *
  * ── Infrastructure ──
  *   - htc_dead_letters_total — dead letter counter
  *   - htc_kafka_messages_sent_total — Kafka messages sent counter
  */
object MetricsServer {

  private val logger = LoggerFactory.getLogger(getClass)
  private var server: Option[HTTPServer] = None

  val simulationTicks: Counter = Counter
    .build()
    .name("htc_simulation_ticks_total")
    .help("Total number of global simulation ticks processed")
    .register()

  val currentTick: Gauge = Gauge
    .build()
    .name("htc_simulation_current_tick")
    .help("Current global simulation tick")
    .register()

  val simulationProgress: Gauge = Gauge
    .build()
    .name("htc_simulation_progress_ratio")
    .help("Simulation progress as ratio of current tick to configured duration [0..1]")
    .register()

  val tickDuration: Histogram = Histogram
    .build()
    .name("htc_tick_duration_seconds")
    .help("Duration of each global tick cycle in seconds (from all-TMs-reported to next broadcast)")
    .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
    .register()

  val actorsRegistered: Counter = Counter
    .build()
    .name("htc_actors_registered_total")
    .help("Total actors registered on local time managers")
    .labelNames("actor_type")
    .register()

  val activeActors: Gauge = Gauge
    .build()
    .name("htc_actors_active")
    .help("Number of active actors by type")
    .labelNames("actor_type")
    .register()

  val eventsProcessed: Counter = Counter
    .build()
    .name("htc_events_processed_total")
    .help("Total spontaneous events dispatched by local time managers")
    .labelNames("event_type")
    .register()

  val tmScheduledActors: Gauge = Gauge
    .build()
    .name("htc_tm_scheduled_actors")
    .help("Number of actors scheduled for current tick on this local TM")
    .register()

  val tmRunningEvents: Gauge = Gauge
    .build()
    .name("htc_tm_running_events")
    .help("Number of spontaneous events currently in-flight on this local TM")
    .register()

  val tmWaitingForProgressive: Gauge = Gauge
    .build()
    .name("htc_tm_waiting_for_progressive")
    .help("1 if GlobalTimeManager is blocked waiting for progressive load, 0 otherwise")
    .register()

  val progressiveActorsCreated: Counter = Counter
    .build()
    .name("htc_progressive_actors_created_total")
    .help("Total actors created via progressive loading")
    .register()

  val progressiveLoadedUpToTick: Gauge = Gauge
    .build()
    .name("htc_progressive_loaded_up_to_tick")
    .help("Highest tick for which all progressive actors are created and initialized")
    .register()

  val progressiveWindowsLoaded: Counter = Counter
    .build()
    .name("htc_progressive_windows_loaded_total")
    .help("Number of progressive tick windows fully loaded")
    .register()

  val journeysCompleted: Counter = Counter
    .build()
    .name("htc_journeys_completed_total")
    .help("Total vehicle journeys completed (arrived at destination)")
    .labelNames("vehicle_type")
    .register()

  val journeysStarted: Counter = Counter
    .build()
    .name("htc_journeys_started_total")
    .help("Total vehicle journeys started")
    .labelNames("vehicle_type")
    .register()

  val deadLetters: Counter = Counter
    .build()
    .name("htc_dead_letters_total")
    .help("Total dead letters in the actor system")
    .register()

  val kafkaMessagesSent: Counter = Counter
    .build()
    .name("htc_kafka_messages_sent_total")
    .help("Total Kafka messages sent")
    .labelNames("topic")
    .register()

  /** Start the Prometheus HTTP metrics server.
    *
    * @param port
    *   port to bind (default 9001)
    */
  def start(port: Int = 9001): Unit = synchronized {
    if (server.isDefined) {
      logger.warn(s"Metrics server already running on port $port")
      return
    }

    try {
      DefaultExports.initialize()

      val httpServer = new HTTPServer.Builder()
        .withPort(port)
        .build()

      server = Some(httpServer)
      logger.info(s"Prometheus metrics server started on port $port — /metrics endpoint ready")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to start Prometheus metrics server on port $port: ${e.getMessage}", e)
    }
  }

  /** Stop the metrics server gracefully.
    */
  def stop(): Unit = synchronized {
    server.foreach {
      s =>
        s.close()
        logger.info("Prometheus metrics server stopped")
    }
    server = None
  }
}
