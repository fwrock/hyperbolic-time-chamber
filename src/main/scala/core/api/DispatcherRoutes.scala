package org.interscity.htc
package core.api

import com.typesafe.config.{Config, ConfigRenderOptions}
import core.util.JsonUtil
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

object DispatcherRoutes {
  private val paths = List(
    "pekko.actor.default-dispatcher.type",
    "pekko.actor.default-dispatcher.executor",
    "pekko.actor.default-dispatcher.fork-join-executor.parallelism-min",
    "pekko.actor.default-dispatcher.fork-join-executor.parallelism-factor",
    "pekko.actor.default-dispatcher.fork-join-executor.parallelism-max",
    "pekko.actor.default-dispatcher.throughput",
    "pekko.actor.io-dispatcher.executor",
    "pekko.actor.io-dispatcher.throughput"
  )

  // Use the ActorSystem's resolved startup config; registry overrides cannot reconfigure dispatchers.
  def values(config: Config): Map[String, String] = paths.filter(config.hasPath).map { path =>
    path -> config.getValue(path).render(ConfigRenderOptions.concise())
  }.toMap

  def routes(config: Config): Route = path("api" / "v1" / "dispatchers") {
    get {
      complete(HttpEntity(ContentTypes.`application/json`, JsonUtil.toJson(
        Map("settings" -> values(config), "restartRequired" -> true))))
    }
  }
}
