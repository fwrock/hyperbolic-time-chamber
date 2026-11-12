package org.interscity.htc
package core.api

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.slf4j.LoggerFactory

import com.fasterxml.jackson.databind.{ JsonNode, ObjectMapper }

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

/** REST routes for htc.* simulator settings. EVERY `htc.*` key of application.conf is adjustable,
  * not only the curated catalog.
  *
  * GET /api/v1/settings[?prefix=htc.report-manager&overridden=true] — all settings with type,
  * default, effective value and source. GET /api/v1/settings/{key}. PUT /api/v1/settings — body is a
  * JSON object, flat ("htc.a.b": 1) or nested ({"htc":{"a":{"b":1}}}); values may be strings,
  * numbers, booleans or arrays; the whole request is validated first and applied atomically. PUT
  * /api/v1/settings/{key} — body is the value (plain or JSON). DELETE /api/v1/settings[/{key}] —
  * clear overrides.
  *
  * Overrides apply to everything the engine reads after the call (before `simulation/start`);
  * keys under startup-only prefixes are stored but need an engine restart (`restartRequired`).
  * Secret values (password/secret/token) are masked in responses.
  */
object SettingsRoutes {

  private val logger = LoggerFactory.getLogger(getClass)
  private val mapper = new ObjectMapper()

  private def json(status: StatusCode, body: String): HttpResponse =
    HttpResponse(status = status, entity = HttpEntity(ContentTypes.`application/json`, body))

  private def entry(key: String): String = {
    val R = SimulatorSettingsRegistry
    val base = R.effective
    val value = R.effectiveValue(key)
    val shown = if (R.isSecret(key) && value.nonEmpty) "***" else value
    val dflt = R.defaultOf(key)
    val shownDefault = if (R.isSecret(key) && dflt.nonEmpty) "***" else dflt
    s"""{"key":${jsonString(key)},"type":"${R.typeOf(base, key)}","envVar":${jsonString(R.envVarOf(key))},""" +
      s""""options":${SimulatorSettingsRegistry.options.get(key).map(_.map(jsonString).mkString("[", ",", "]")).getOrElse("null")},""" +
      s""""description":${jsonString(R.describe(key))},"defaultValue":${jsonString(shownDefault)},""" +
      s""""currentValue":${jsonString(shown)},"source":"${R.effectiveSource(key)}",""" +
      s""""overridden":${R.get(key).isDefined},"restartRequired":${R.isStartupOnly(key)}}"""
  }

  /** Flatten a JSON object to dotted keys → string values; arrays stay as JSON text. */
  private[api] def flatten(node: JsonNode, prefix: String, out: mutable.LinkedHashMap[String, String]): Unit =
    if (node.isObject && !(prefix.nonEmpty && SimulatorSettingsRegistry.typeOf(SimulatorSettingsRegistry.effective, prefix) == "string" && SimulatorSettingsRegistry.effective.hasPath(prefix))) {
      node.fields().asScala.foreach {
        e => flatten(e.getValue, if (prefix.isEmpty) e.getKey else prefix + "." + e.getKey, out)
      }
    } else if (node.isTextual) out.put(prefix, node.asText())
    else if (node.isNull) throw new IllegalArgumentException(s"null is not allowed for '$prefix' (DELETE the override instead)")
    else out.put(prefix, node.toString)

  private[api] def parseBody(body: String): Map[String, String] = {
    val node = mapper.readTree(body)
    if (node == null || !node.isObject) throw new IllegalArgumentException("body must be a JSON object")
    val out = mutable.LinkedHashMap.empty[String, String]
    flatten(node, "", out)
    out.toMap
  }

  def routes(implicit mat: Materializer, ec: ExecutionContext): Route =
    pathPrefix("api" / "v1" / "settings") {
      concat(
        pathEndOrSingleSlash {
          concat(
            get {
              parameters("prefix".withDefault(""), "overridden".withDefault("false")) {
                (prefix, onlyOverridden) =>
                  val keys = (SimulatorSettingsRegistry.allKeys ++ SimulatorSettingsRegistry.getAll.keys).distinct.sorted
                    .filter(_.startsWith(prefix))
                    .filter(
                      k => onlyOverridden != "true" || SimulatorSettingsRegistry.get(k).isDefined
                    )
                  complete(json(StatusCodes.OK, s"""{"count":${keys.size},"settings":[${keys.map(entry).mkString(",")}]}"""))
              }
            },
            put {
              entity(as[String]) {
                body =>
                  try {
                    val parsed = parseBody(body)
                    val errors = parsed.flatMap { case (k, v) => SimulatorSettingsRegistry.validate(k, v) }.toList
                    if (errors.nonEmpty)
                      complete(json(StatusCodes.BadRequest, s"""{"status":"error","errors":[${errors.map(jsonString).mkString(",")}]}"""))
                    else {
                      SimulatorSettingsRegistry.setAll(parsed)
                      logger.info(s"Settings API: ${parsed.size} setting(s) updated")
                      val restart = parsed.keys.filter(SimulatorSettingsRegistry.isStartupOnly).toList.sorted
                      complete(json(StatusCodes.OK,
                        s"""{"status":"ok","updated":${parsed.size},"keys":[${parsed.keys.toList.sorted.map(jsonString).mkString(",")}],"restartRequired":[${restart.map(jsonString).mkString(",")}]}"""))
                    }
                  } catch {
                    case e: Exception =>
                      complete(json(StatusCodes.BadRequest, s"""{"status":"error","message":${jsonString(e.getMessage)}}"""))
                  }
              }
            },
            delete {
              SimulatorSettingsRegistry.clearAll()
              logger.info("Settings API: all overrides cleared")
              complete(json(StatusCodes.OK, """{"status":"ok","message":"All setting overrides cleared"}"""))
            }
          )
        },
        path(Remaining) {
          key =>
            val decodedKey = java.net.URLDecoder.decode(key, "UTF-8")
            concat(
              get {
                complete(json(StatusCodes.OK, entry(decodedKey)))
              },
              put {
                entity(as[String]) {
                  rawValue =>
                    val v = rawValue.trim
                    val value = if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v.substring(1, v.length - 1) else v
                    SimulatorSettingsRegistry.validate(decodedKey, value) match {
                      case Some(err) =>
                        complete(json(StatusCodes.BadRequest, s"""{"status":"error","message":${jsonString(err)}}"""))
                      case None =>
                        SimulatorSettingsRegistry.set(decodedKey, value)
                        logger.info(s"Settings API: $decodedKey updated")
                        complete(json(StatusCodes.OK,
                          s"""{"status":"ok","key":${jsonString(decodedKey)},"restartRequired":${SimulatorSettingsRegistry.isStartupOnly(decodedKey)}}"""))
                    }
                }
              },
              delete {
                SimulatorSettingsRegistry.clear(decodedKey)
                logger.info(s"Settings API: override cleared for $decodedKey")
                complete(json(StatusCodes.OK, s"""{"status":"ok","message":${jsonString("Override cleared for " + decodedKey)}}"""))
              }
            )
        }
      )
    }

  private def jsonString(s: String): String =
    "\"" + (if (s == null) "" else s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")) + "\""
}
