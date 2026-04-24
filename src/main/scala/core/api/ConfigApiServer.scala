package org.interscity.htc
package core.api

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.concat
import org.apache.pekko.stream.Materializer
import org.slf4j.LoggerFactory

/** Optional HTTP server that exposes the full simulator management REST API.
  *
  * Disabled by default — zero impact on the existing env-var / application.conf
  * / JSON-file configuration approach when not enabled.
  *
  * Enable:
  *   env:    HTC_API_ENABLED=true    (or htc.api.enabled = true in application.conf)
  *   port:   HTC_API_PORT=8080       (or htc.api.port = 8080, default 8080)
  *
  * When enabled, [[SimulationManager]] will NOT auto-start — the simulation
  * waits for a POST /api/v1/simulation/start call.
  *
  * Exposed endpoints:
  *   GET/PUT/DELETE /api/v1/simulation/config   — scenario JSON
  *   GET            /api/v1/simulation/status   — status + tick metrics
  *   POST           /api/v1/simulation/start    — start (optional settings override)
  *   POST           /api/v1/simulation/pause
  *   POST           /api/v1/simulation/resume
  *   POST           /api/v1/simulation/stop
  *   GET/PUT/DELETE /api/v1/settings            — htc.* settings catalog
  *   GET/PUT/DELETE /api/v1/settings/{key}
  *   GET            /api/v1/health
  */
object ConfigApiServer {

  private val logger = LoggerFactory.getLogger(getClass)

  def start(system: ActorSystem): Unit = {
    val apiEnabled = sys.env
      .getOrElse("HTC_API_ENABLED",
        try system.settings.config.getString("htc.api.enabled")
        catch { case _: Exception => "false" })
      .equalsIgnoreCase("true")

    // Always initialise the controller — even when the API is disabled it tracks
    // that we are in auto-start mode (status = Running immediately).
    SimulationController.initialize(system, apiEnabled)

    if (!apiEnabled) {
      logger.info("Simulator API disabled (HTC_API_ENABLED not set). Running in env/file config mode.")
      return
    }

    val port = sys.env
      .get("HTC_API_PORT")
      .flatMap(_.toIntOption)
      .getOrElse(
        try system.settings.config.getInt("htc.api.port")
        catch { case _: Exception => 8080 }
      )

    implicit val actorSystem: ActorSystem = system
    implicit val mat: Materializer        = Materializer(system)
    implicit val ec                       = system.dispatcher

    val allowedOrigins = sys.env
      .getOrElse("HTC_API_CORS_ORIGINS",
        try system.settings.config.getString("htc.api.cors-origins")
        catch { case _: Exception => "*" })

    val scenariosDir = sys.env
      .getOrElse("HTC_SCENARIOS_DIR",
        try system.settings.config.getString("htc.api.scenarios-dir")
        catch { case _: Exception => "/app/simulations" })

    ScenarioRegistry.configure(scenariosDir)

    val allRoutes = CorsSupport.cors(allowedOrigins)(
      concat(SimulationRoutes.routes, SettingsRoutes.routes, ScenarioRoutes.routes)
    )

    Http(system)
      .newServerAt("0.0.0.0", port)
      .bind(allRoutes)
      .foreach { binding =>
        logger.info(
          s"Simulator API listening at http://0.0.0.0:${binding.localAddress.getPort}/api/v1"
        )
        logger.info("Simulation is IDLE — send POST /api/v1/simulation/start to begin")
      }
  }
}

