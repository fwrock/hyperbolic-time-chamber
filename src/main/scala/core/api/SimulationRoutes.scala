package org.interscity.htc
package core.api

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.core.metrics.core.SimulationMetrics
import org.interscity.htc.core.util.JsonUtil
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

/** REST routes for simulation scenario management and lifecycle control.
  *
  * ── Scenario config ────────────────────────────────────────────────────── GET
  * /api/v1/simulation/config — current scenario (JSON) and its source PUT /api/v1/simulation/config
  * — load a Simulation JSON as API override DELETE /api/v1/simulation/config — clear API override
  * (falls back to env/file)
  *
  * ── Lifecycle ──────────────────────────────────────────────────────────── GET
  * /api/v1/simulation/status — current status + tick metrics POST /api/v1/simulation/start — start
  * simulation (optional body, see below) POST /api/v1/simulation/pause — pause ticking (simulation
  * must be Running) POST /api/v1/simulation/resume — resume ticking (simulation must be Paused)
  * POST /api/v1/simulation/stop — stop and clean up
  *
  * ── /start optional JSON body ───────────────────────────────────────────── { "configFile":
  * "/optional/path/to/simulation.json", "settings": { "htc.time-manager.total-instances": "64",
  * "htc.report-manager.json.batch-size": "200" } } Both fields are optional. When "configFile" is
  * absent the scenario is resolved from [[ApiConfigRegistry]] or the normal env-var /
  * application.conf chain.
  */
object SimulationRoutes {

  private val logger = LoggerFactory.getLogger(getClass)

  def routes(implicit mat: Materializer, ec: ExecutionContext): Route =
    pathPrefix("api" / "v1") {
      concat(
        path("health") {
          get {
            complete(
              ok(
                s"""{"status":"ok","simulationStatus":"${SimulationController.status}","apiConfigLoaded":${ApiConfigRegistry.hasConfig}}"""
              )
            )
          }
        },
        pathPrefix("simulation") {
          concat(
            path("config") {
              concat(
                get {
                  val (source, json) = ApiConfigRegistry.get match {
                    case Some(config) =>
                      ("api_override", safeToJson(config))
                    case None =>
                      try
                        (
                          "file_or_env",
                          safeToJson(core.util.SimulationUtil.loadSimulationConfigFromFileOrEnv())
                        )
                      catch {
                        case e: Exception => ("none", s"""{"error":${jsonString(e.getMessage)}}""")
                      }
                  }
                  complete(ok(s"""{"source":"$source","config":$json}"""))
                },
                put {
                  entity(as[String]) {
                    body =>
                      try {
                        val sim = JsonUtil.fromJson[Simulation](body)
                        ApiConfigRegistry.set(sim)
                        logger.info(s"Simulation config API: scenario '${sim.name}' loaded via PUT")
                        complete(ok(s"""{"status":"ok","name":${jsonString(sim.name)}}"""))
                      } catch {
                        case e: Exception =>
                          complete(badRequest(s"""{"status":"error","message":${jsonString(
                              e.getMessage
                            )}}"""))
                      }
                  }
                },
                delete {
                  ApiConfigRegistry.clear()
                  logger.info("Simulation config API: API override cleared")
                  complete(ok("""{"status":"ok","message":"Scenario override cleared"}"""))
                }
              )
            },
            path("status") {
              get {
                val s = SimulationController.status
                val currentTick = SimulationMetrics.currentTick.get().toLong
                val progress = SimulationMetrics.simulationProgress.get()
                complete(
                  ok(
                    s"""|{
                      |  "status": "$s",
                      |  "currentTick": $currentTick,
                      |  "progressRatio": $progress,
                      |  "apiConfigLoaded": ${ApiConfigRegistry.hasConfig},
                      |  "settingOverrides": ${SimulatorSettingsRegistry.getAll.size}
                      |}""".stripMargin
                  )
                )
              }
            },
            path("start") {
              post {
                entity(as[String]) {
                  body =>
                    val (configFile, settings) = parseStartBody(body)
                    SimulationController.start(configFile, settings) match {
                      case Right(_) =>
                        complete(ok("""{"status":"ok","message":"Simulation start triggered"}"""))
                      case Left(err) =>
                        complete(conflict(s"""{"status":"error","message":${jsonString(err)}}"""))
                    }
                }
              }
            },
            path("pause") {
              post {
                SimulationController.pause() match {
                  case Right(_) => complete(ok("""{"status":"ok","message":"Simulation paused"}"""))
                  case Left(err) =>
                    complete(conflict(s"""{"status":"error","message":${jsonString(err)}}"""))
                }
              }
            },
            path("resume") {
              post {
                SimulationController.resume() match {
                  case Right(_) =>
                    complete(ok("""{"status":"ok","message":"Simulation resumed"}"""))
                  case Left(err) =>
                    complete(conflict(s"""{"status":"error","message":${jsonString(err)}}"""))
                }
              }
            },
            path("stop") {
              post {
                SimulationController.stop() match {
                  case Right(_) =>
                    complete(ok("""{"status":"ok","message":"Simulation stopped"}"""))
                  case Left(err) =>
                    complete(conflict(s"""{"status":"error","message":${jsonString(err)}}"""))
                }
              }
            }
          )
        }
      )
    }

  private def ok(json: String) =
    HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, json))

  private def badRequest(json: String) =
    HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`application/json`, json))

  private def conflict(json: String) =
    HttpResponse(StatusCodes.Conflict, entity = HttpEntity(ContentTypes.`application/json`, json))

  private def safeToJson(sim: Simulation): String =
    try JsonUtil.toJson(sim)
    catch { case e: Exception => s"""{"error":${jsonString(e.getMessage)}}""" }

  private def jsonString(s: String): String =
    "\"" + (if (s == null) ""
            else s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")) + "\""

  /** Parses the optional /start body. Accepts: {} | {"configFile":"..."} | {"settings":{...}} |
    * {"configFile":"...","settings":{...}}
    */
  private def parseStartBody(body: String): (Option[String], Map[String, String]) = {
    if (body.isBlank) return (None, Map.empty)
    try {
      val configFile = extractJsonString(body, "configFile")
      val settings = extractJsonObject(body, "settings")
      (configFile.filter(_.nonEmpty), settings)
    } catch {
      case _: Exception => (None, Map.empty)
    }
  }

  private def extractJsonString(rawJson: String, field: String): Option[String] = {
    val pattern = s""""$field"\\s*:\\s*"([^"]*)"""".r
    pattern.findFirstMatchIn(rawJson).map(_.group(1))
  }

  private def extractJsonObject(rawJson: String, field: String): Map[String, String] = {
    val start = rawJson.indexOf(s""""$field"""")
    if (start < 0) return Map.empty
    val braceOpen = rawJson.indexOf('{', start + field.length + 2)
    if (braceOpen < 0) return Map.empty
    var depth = 0
    var i = braceOpen
    while (i < rawJson.length) {
      if (rawJson.charAt(i) == '{') depth += 1
      else if (rawJson.charAt(i) == '}') {
        depth -= 1;
        if (depth == 0) {
          val inner = rawJson.substring(braceOpen + 1, i)
          return inner
            .split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
            .map(_.trim)
            .filter(_.nonEmpty)
            .flatMap {
              pair =>
                val parts = pair.split(":", 2)
                if (parts.length == 2)
                  Some(
                    parts(0).trim.stripPrefix("\"").stripSuffix("\"") ->
                      parts(1).trim.stripPrefix("\"").stripSuffix("\"")
                  )
                else None
            }
            .toMap
        }
      }
      i += 1
    }
    Map.empty
  }
}
