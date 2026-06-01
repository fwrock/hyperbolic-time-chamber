package org.interscity.htc
package core.metrics

import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.LoggerFactory

/** Prometheus HTTP metrics server.
  *
  * Exposes all registered metrics on the configured port (default 9001) at /metrics.
  *
  * Automatically registered JVM collectors (via [[DefaultExports]]):
  *   - JVM memory (heap, non-heap, pools)
  *   - JVM GC (pause time, count per collector)
  *   - JVM threads (count, states, daemon)
  *   - JVM classloading
  *   - Process CPU, open file descriptors, start time
  *
  * Metrics are defined in separate objects by concern:
  *   - [[SimulationMetrics]] — tick progress
  *   - [[ActorMetrics]] — actor lifecycle and message exchange
  *   - [[TimeManagerMetrics]] — time manager state
  *   - [[ProgressiveLoadingMetrics]] — progressive loading
  *   - [[PhaseMetrics]] — simulation phase timing
  *   - [[InfrastructureMetrics]] — dead letters, Kafka
  *   - [[core.metrics.model.hybrid.MovableMetrics]] — vehicle journey outcomes
  *   - [[core.metrics.model.hybrid.GPSMetrics]] — GPS routing failures
  *   - [[core.metrics.model.hybrid.PersonMetrics]] — person activity and trip metrics
  *   - [[core.metrics.model.hybrid.LinkMetrics]] — road link flow and travel time
  *   - [[core.metrics.model.hybrid.TrafficSignalMetrics]] — traffic signal phase changes
  */
object MetricsServer {

  private val logger = LoggerFactory.getLogger(getClass)
  private var server: Option[HTTPServer] = None

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

  /** Stop the metrics server gracefully. */
  def stop(): Unit = synchronized {
    server.foreach { s =>
      s.close()
      logger.info("Prometheus metrics server stopped")
    }
    server = None
  }
}
