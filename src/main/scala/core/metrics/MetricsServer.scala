package org.interscity.htc
package core.metrics

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Histogram
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress

/**
 * Prometheus metrics server for the HTC simulation.
 *
 * Exposes JVM metrics (heap, GC, threads, CPU) and custom simulation metrics
 * on the configured port (default 9001) at /metrics endpoint.
 *
 * Automatically registered collectors:
 *   - JVM memory (heap, non-heap, pools)
 *   - JVM GC (pause time, count per collector)
 *   - JVM threads (count, states, daemon)
 *   - JVM classloading
 *   - Process CPU, open file descriptors, start time
 *
 * Custom simulation metrics:
 *   - htc_simulation_ticks_total — global tick counter
 *   - htc_actors_active — gauge of active actors by type
 *   - htc_events_processed_total — events processed counter by type
 *   - htc_dead_letters_total — dead letter counter
 *   - htc_tick_duration_seconds — histogram of tick processing time
 *   - htc_kafka_messages_sent_total — Kafka messages sent counter
 */
object MetricsServer {

  private val logger = LoggerFactory.getLogger(getClass)
  private var server: Option[HTTPServer] = None

  // ── Custom simulation metrics ──────────────────────────────────

  val simulationTicks: Counter = Counter.build()
    .name("htc_simulation_ticks_total")
    .help("Total number of simulation ticks processed")
    .register()

  val activeActors: Gauge = Gauge.build()
    .name("htc_actors_active")
    .help("Number of active actors by type")
    .labelNames("actor_type")
    .register()

  val eventsProcessed: Counter = Counter.build()
    .name("htc_events_processed_total")
    .help("Total simulation events processed")
    .labelNames("event_type")
    .register()

  val deadLetters: Counter = Counter.build()
    .name("htc_dead_letters_total")
    .help("Total dead letters in the actor system")
    .register()

  val tickDuration: Histogram = Histogram.build()
    .name("htc_tick_duration_seconds")
    .help("Duration of each simulation tick in seconds")
    .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
    .register()

  val kafkaMessagesSent: Counter = Counter.build()
    .name("htc_kafka_messages_sent_total")
    .help("Total Kafka messages sent")
    .labelNames("topic")
    .register()

  /**
   * Start the Prometheus HTTP metrics server.
   *
   * @param port port to bind (default 9001)
   */
  def start(port: Int = 9001): Unit = synchronized {
    if (server.isDefined) {
      logger.warn(s"Metrics server already running on port $port")
      return
    }

    try {
      // Register all default JVM collectors (memory, GC, threads, CPU, etc.)
      DefaultExports.initialize()

      // Start HTTP server
      val httpServer = new HTTPServer.Builder()
        .withPort(port)
        .build()

      server = Some(httpServer)
      logger.info(s"✅ Prometheus metrics server started on port $port — /metrics endpoint ready")
    } catch {
      case e: Exception =>
        logger.error(s"❌ Failed to start Prometheus metrics server on port $port: ${e.getMessage}", e)
    }
  }

  /**
   * Stop the metrics server gracefully.
   */
  def stop(): Unit = synchronized {
    server.foreach { s =>
      s.close()
      logger.info("Prometheus metrics server stopped")
    }
    server = None
  }
}
