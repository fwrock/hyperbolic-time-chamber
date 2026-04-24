package org.interscity.htc
package core.api

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.interscity.htc.core.util.JsonUtil
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

/** REST routes for browsing and loading available simulation scenarios.
  *
  * Scenarios are read from the directory configured via `HTC_SCENARIOS_DIR`
  * (or `htc.api.scenarios-dir` / default `/app/simulations`).
  *
  * Each scenario is a sub-directory containing:
  *   - `simulation.json`  — required, the [[core.entity.configuration.Simulation]] config
  *   - `metadata.json`    — optional, human/tooling metadata (description, tags, author…)
  *
  * ── Endpoints ────────────────────────────────────────────────────────────
  * GET  /api/v1/scenarios              — list all scenarios with summary + metadata
  * GET  /api/v1/scenarios/{name}       — full detail: metadata + complete simulation config
  * POST /api/v1/scenarios/{name}/load  — load scenario into [[ApiConfigRegistry]]
  *                                       (equivalent to PUT /api/v1/simulation/config with
  *                                        the scenario's simulation.json content)
  */
object ScenarioRoutes {

  private val logger = LoggerFactory.getLogger(getClass)

  def routes(implicit mat: Materializer, ec: ExecutionContext): Route =
    pathPrefix("api" / "v1" / "scenarios") {
      concat(

        // ── GET /api/v1/scenarios ──────────────────────────────────────────
        pathEndOrSingleSlash {
          get {
            val scenarios = ScenarioRegistry.listScenarios()
            val items = scenarios.map { s =>
              val metaJson = s.meta.map(m => safeToJson(m)).getOrElse("null")
              s"""|{
                  |  "name": ${jsonStr(s.name)},
                  |  "hasMetadata": ${s.hasMetadata},
                  |  "meta": $metaJson,
                  |  "simulationName": ${optStr(s.simulationName)},
                  |  "simulationDescription": ${optStr(s.simulationDescription)},
                  |  "duration": ${s.duration.map(_.toString).getOrElse("null")},
                  |  "timeUnit": ${optStr(s.timeUnit)},
                  |  "startTick": ${s.startTick.map(_.toString).getOrElse("null")},
                  |  "endTick": ${s.endTick.map(_.toString).getOrElse("null")}
                  |}""".stripMargin
            }
            val body =
              s"""|{
                  |  "scenariosDir": ${jsonStr(ScenarioRegistry.directory)},
                  |  "count": ${scenarios.size},
                  |  "scenarios": [${items.mkString(",")}]
                  |}""".stripMargin
            complete(ok(body))
          }
        },

        pathPrefix(Segment) { name =>
          concat(

            // ── GET /api/v1/scenarios/{name} ────────────────────────────────
            pathEndOrSingleSlash {
              get {
                ScenarioRegistry.getScenario(name) match {
                  case Left(err) =>
                    complete(notFound(s"""{"status":"error","message":${jsonStr(err)}}"""))
                  case Right(detail) =>
                    val metaJson = detail.meta.map(m => safeToJson(m)).getOrElse("null")
                    val simJson  = safeToJson(detail.simulation)
                    val body =
                      s"""|{
                          |  "name": ${jsonStr(detail.name)},
                          |  "hasMetadata": ${detail.hasMetadata},
                          |  "meta": $metaJson,
                          |  "simulation": $simJson
                          |}""".stripMargin
                    complete(ok(body))
                }
              }
            },

            // ── POST /api/v1/scenarios/{name}/load ─────────────────────────
            path("load") {
              post {
                ScenarioRegistry.getScenario(name) match {
                  case Left(err) =>
                    complete(notFound(s"""{"status":"error","message":${jsonStr(err)}}"""))
                  case Right(detail) =>
                    ApiConfigRegistry.set(detail.simulation)
                    logger.info(
                      s"ScenarioRoutes: scenario '$name' (simulation '${detail.simulation.name}') loaded into ApiConfigRegistry"
                    )
                    val body =
                      s"""|{
                          |  "status": "ok",
                          |  "message": "Scenario loaded — call POST /api/v1/simulation/start to begin",
                          |  "name": ${jsonStr(name)},
                          |  "simulationName": ${jsonStr(detail.simulation.name)}
                          |}""".stripMargin
                    complete(ok(body))
                }
              }
            }
          )
        }
      )
    }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def ok(json: String) =
    HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, json))

  private def notFound(json: String) =
    HttpResponse(StatusCodes.NotFound, entity = HttpEntity(ContentTypes.`application/json`, json))

  private def safeToJson(value: AnyRef): String =
    try JsonUtil.toJson(value)
    catch { case e: Exception => s"""{"serializationError":${jsonStr(e.getMessage)}}""" }

  private def jsonStr(s: String): String =
    if (s == null) "null"
    else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  private def optStr(opt: Option[String]): String = opt.map(jsonStr).getOrElse("null")
}
