package org.interscity.htc
package core.api

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

class SettingsRoutesHttpSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystem(
    "settings-http-spec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.remote.artery.canonical.port = 0")
      .withFallback(ConfigFactory.load())
  )
  import system.dispatcher
  private val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(SettingsRoutes.routes), 10.seconds)
  private val base = s"http://127.0.0.1:${binding.localAddress.getPort}/api/v1/settings"

  override def afterAll(): Unit = {
    SimulatorSettingsRegistry.clearAll()
    Await.result(system.terminate(), 10.seconds)
  }

  private def call(method: HttpMethod, path: String, body: String = ""): (Int, String) = {
    val req = HttpRequest(method, base + path, entity = HttpEntity(ContentTypes.`application/json`, body))
    val res = Await.result(Http().singleRequest(req), 10.seconds)
    (res.status.intValue, Await.result(Unmarshal(res.entity).to[String], 10.seconds))
  }

  "GET /settings" should "list every htc.* key with type and options" in {
    val (code, body) = call(HttpMethods.GET, "?prefix=htc.report-manager.parquet")
    code shouldBe 200
    body should include("\"key\":\"htc.report-manager.parquet.compression\"")
    body should include("\"options\":[\"snappy\",\"zstd\",\"gzip\",\"uncompressed\"]")
    call(HttpMethods.GET, "")._2 should include("htc.routing.ch.rebuild-block-threshold-factor")
  }

  "PUT /settings" should "apply nested bodies atomically and reject bad ones without applying any" in {
    val (bad, msg) = call(HttpMethods.PUT, "", """{"htc.warmup.max-workers-per-node":5,"htc.report-manager.parquet.compression":"lzo"}""")
    bad shouldBe 400
    msg should include("must be one of")
    SimulatorSettingsRegistry.get("htc.warmup.max-workers-per-node") shouldBe None

    val (ok, _) = call(HttpMethods.PUT, "", """{"htc":{"warmup":{"max-workers-per-node":5}},"htc.routing.ch.rebuild-scheduled-ticks":[3]}""")
    ok shouldBe 200
    SimulatorSettingsRegistry.effective.getInt("htc.warmup.max-workers-per-node") shouldBe 5
    call(HttpMethods.GET, "/htc.warmup.max-workers-per-node")._2 should include("\"source\":\"api_override\"")
    call(HttpMethods.GET, "?overridden=true")._2 should include("\"count\":2")
  }

  "secrets" should "be masked and DELETE should restore defaults" in {
    call(HttpMethods.PUT, "/htc.report-manager.clickhouse.password", "hunter2")._1 shouldBe 200
    val (_, got) = call(HttpMethods.GET, "/htc.report-manager.clickhouse.password")
    got should not include "hunter2"
    call(HttpMethods.DELETE, "")._1 shouldBe 200
    SimulatorSettingsRegistry.getAll shouldBe empty
  }
}
