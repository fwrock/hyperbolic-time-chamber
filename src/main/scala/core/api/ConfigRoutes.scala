package org.interscity.htc
package core.api

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.core.util.JsonUtil
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

/** Pekko HTTP routes for the optional simulator configuration REST API.
  *
  * Endpoints:
  *   GET    /api/v1/config  — returns the active configuration and its source
  *   PUT    /api/v1/config  — loads a Simulation JSON as the API override
  *   DELETE /api/v1/config  — clears the API override (falls back to env/file)
  *   GET    /api/v1/health  — liveness check
  */
object ConfigRoutes {

  private val logger = LoggerFactory.getLogger(getClass)

  def routes(implicit mat: Materializer, ec: ExecutionContext): Route =
    pathPrefix("api" / "v1") {
      concat(
        path("config") {
          concat(
            get {
              val (source, json) = ApiConfigRegistry.get match {
                case Some(config) =>
                  ("api", JsonUtil.toJson(config))
                case None =>
                  try {
                    val loaded = core.util.SimulationUtil.loadSimulationConfigFromFileOrEnv()
                    ("file_or_env", JsonUtil.toJson(loaded))
                  } catch {
                    case e: Exception =>
                      logger.warn(s"Config API GET: could not load config from file/env: ${e.getMessage}")
                      ("none", s"""{"error":"${e.getMessage.replace("\"", "'")}"}""")
                  }
              }
              complete(
                HttpResponse(
                  status = StatusCodes.OK,
                  entity = HttpEntity(
                    ContentTypes.`application/json`,
                    s"""{"source":"$source","config":$json}"""
                  )
                )
              )
            },
            put {
              entity(as[String]) { body =>
                try {
                  val simulation = JsonUtil.fromJson[Simulation](body)
                  ApiConfigRegistry.set(simulation)
                  logger.info(s"Config API: simulation '${simulation.name}' loaded via PUT /api/v1/config")
                  complete(
                    HttpResponse(
                      status = StatusCodes.OK,
                      entity = HttpEntity(
                        ContentTypes.`application/json`,
                        s"""{"status":"ok","message":"Configuration loaded","name":"${simulation.name}"}"""
                      )
                    )
                  )
                } catch {
                  case e: Exception =>
                    logger.warn(s"Config API PUT: invalid body — ${e.getMessage}")
                    complete(
                      HttpResponse(
                        status = StatusCodes.BadRequest,
                        entity = HttpEntity(
                          ContentTypes.`application/json`,
                          s"""{"status":"error","message":"${e.getMessage.replace("\"", "'")}"}"""
                        )
                      )
                    )
                }
              }
            },
            delete {
              ApiConfigRegistry.clear()
              logger.info("Config API: API config override cleared via DELETE /api/v1/config")
              complete(
                HttpResponse(
                  status = StatusCodes.OK,
                  entity = HttpEntity(
                    ContentTypes.`application/json`,
                    """{"status":"ok","message":"API config override cleared"}"""
                  )
                )
              )
            }
          )
        },
        path("health") {
          get {
            complete(
              HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(
                  ContentTypes.`application/json`,
                  s"""{"status":"ok","apiConfigLoaded":${ApiConfigRegistry.hasConfig}}"""
                )
              )
            )
          }
        }
      )
    }
}
