package org.interscity.htc
package core.api

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

/** REST routes for htc.* simulator settings.
  *
  * GET  /api/v1/settings             — all known settings with current effective values
  * GET  /api/v1/settings/{key}       — a single setting by config-path key
  * PUT  /api/v1/settings             — set multiple settings (body: JSON object)
  * PUT  /api/v1/settings/{key}       — set a single setting (body: plain value string)
  * DELETE /api/v1/settings           — clear all API overrides
  * DELETE /api/v1/settings/{key}     — clear a specific API override
  *
  * Key format: use the htc.* config path, e.g. "htc.time-manager.total-instances".
  * The env-var equivalent is shown in the GET response for reference.
  */
object SettingsRoutes {

  private val logger = LoggerFactory.getLogger(getClass)

  def routes(implicit mat: Materializer, ec: ExecutionContext): Route =
    pathPrefix("api" / "v1" / "settings") {
      concat(
        pathEndOrSingleSlash {
          concat(
            get {
              val entries = SimulatorSettingsRegistry.catalog.map { d =>
                val value  = SimulatorSettingsRegistry.effectiveValue(d.configPath)
                val source = SimulatorSettingsRegistry.effectiveSource(d.configPath)
                s"""|    {
                    |      "key": "${d.configPath}",
                    |      "envVar": "${d.envVar}",
                    |      "description": "${d.description}",
                    |      "defaultValue": "${d.defaultValue}",
                    |      "currentValue": ${jsonString(value)},
                    |      "source": "$source"
                    |    }""".stripMargin
              }.mkString(",\n")

              complete(HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(ContentTypes.`application/json`,
                  s"""{\n  "settings": [\n$entries\n  ]\n}""")
              ))
            },
            put {
              entity(as[String]) { body =>
                try {
                  val parsed = parseJsonObject(body)
                  SimulatorSettingsRegistry.setAll(parsed)
                  logger.info(s"Settings API: ${parsed.size} setting(s) updated")
                  complete(HttpResponse(
                    status = StatusCodes.OK,
                    entity = HttpEntity(ContentTypes.`application/json`,
                      s"""{"status":"ok","updated":${parsed.size}}""")
                  ))
                } catch {
                  case e: Exception =>
                    complete(HttpResponse(
                      status = StatusCodes.BadRequest,
                      entity = HttpEntity(ContentTypes.`application/json`,
                        s"""{"status":"error","message":${jsonString(e.getMessage)}}""")
                    ))
                }
              }
            },
            delete {
              SimulatorSettingsRegistry.clearAll()
              logger.info("Settings API: all overrides cleared")
              complete(HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(ContentTypes.`application/json`,
                  """{"status":"ok","message":"All setting overrides cleared"}""")
              ))
            }
          )
        },
        path(Remaining) { key =>
          concat(
            get {
              val decodedKey = java.net.URLDecoder.decode(key, "UTF-8")
              val value  = SimulatorSettingsRegistry.effectiveValue(decodedKey)
              val source = SimulatorSettingsRegistry.effectiveSource(decodedKey)
              val meta   = SimulatorSettingsRegistry.catalog.find(_.configPath == decodedKey)
              complete(HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(ContentTypes.`application/json`,
                  s"""|{
                      |  "key": ${jsonString(decodedKey)},
                      |  "envVar": ${jsonString(meta.map(_.envVar).getOrElse(""))},
                      |  "currentValue": ${jsonString(value)},
                      |  "source": "$source"
                      |}""".stripMargin)
              ))
            },
            put {
              entity(as[String]) { rawValue =>
                val decodedKey = java.net.URLDecoder.decode(key, "UTF-8")
                val value      = rawValue.trim.stripPrefix("\"").stripSuffix("\"")
                SimulatorSettingsRegistry.set(decodedKey, value)
                logger.info(s"Settings API: $decodedKey = $value")
                complete(HttpResponse(
                  status = StatusCodes.OK,
                  entity = HttpEntity(ContentTypes.`application/json`,
                    s"""{"status":"ok","key":${jsonString(decodedKey)},"value":${jsonString(value)}}""")
                ))
              }
            },
            delete {
              val decodedKey = java.net.URLDecoder.decode(key, "UTF-8")
              SimulatorSettingsRegistry.clear(decodedKey)
              logger.info(s"Settings API: override cleared for $decodedKey")
              complete(HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(ContentTypes.`application/json`,
                  s"""{"status":"ok","message":"Override cleared for ${decodedKey}"}""")
              ))
            }
          )
        }
      )
    }

  /** Naïve JSON object parser (no deps). Handles flat {"key":"value",...} bodies. */
  private def parseJsonObject(json: String): Map[String, String] = {
    val body = json.trim.stripPrefix("{").stripSuffix("}")
    body.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { pair =>
        val parts = pair.split(":", 2)
        if (parts.length == 2) {
          val k = parts(0).trim.stripPrefix("\"").stripSuffix("\"")
          val v = parts(1).trim.stripPrefix("\"").stripSuffix("\"")
          Some(k -> v)
        } else None
      }
      .toMap
  }

  private def jsonString(s: String): String =
    "\"" + (if (s == null) "" else s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\""
}
