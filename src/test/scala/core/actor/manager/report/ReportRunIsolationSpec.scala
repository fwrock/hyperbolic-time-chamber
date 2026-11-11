package org.interscity.htc
package core.actor.manager.report

import avro.simulation.SimulationReportEventAvro
import com.typesafe.config.ConfigFactory
import core.entity.event.control.report.ReportEvent
import core.runtime.accounting.*
import core.util.JsonUtil
import org.apache.avro.generic.GenericRecord
import org.apache.parquet.avro.AvroParquetReader
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.time.LocalDateTime
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import model.mobility.entity.event.data.VehicleLinkFlowData
import com.fasterxml.jackson.databind.ObjectMapper

class ReportRunIsolationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val directory = Files.createTempDirectory("htc-report-isolation-")
  private val json = new ObjectMapper()
  private val requests = new ConcurrentLinkedQueue[(String, String)]()
  private val http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  http.createContext("/", exchange => {
    val query = Option(exchange.getRequestURI.getRawQuery).getOrElse("")
    val body = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
    requests.add((URLDecoder.decode(query, UTF_8), body))
    val response = "Ok.\n".getBytes(UTF_8)
    exchange.sendResponseHeaders(200, response.length)
    exchange.getResponseBody.write(response)
    exchange.close()
  })
  http.start()
  private implicit val system: ActorSystem = ActorSystem("ReportRunIsolationSpec",
    ConfigFactory.parseString(s"""
      pekko.actor.provider = local
      pekko.actor.fail-mixed-versions = off
      htc.report-manager.csv.directory = "${directory}/csv"
      htc.report-manager.json.directory = "${directory}/json"
      htc.report-manager.parquet.directory = "${directory}/parquet"
      htc.report-manager.clickhouse.host = "127.0.0.1"
      htc.report-manager.clickhouse.port = ${http.getAddress.getPort}
    """).withFallback(ConfigFactory.load()))

  override def afterAll(): Unit = { system.terminate(); http.stop(0); () }

  private val start = LocalDateTime.of(2026, 1, 1, 0, 0)
  private val event = ReportEvent(entityId = "vehicle-1", tick = 10L, lamportTick = 0L,
    label = "sample", data = Map("speed" -> 10))

  private def files(path: Path): Vector[Path] = {
    val stream = Files.list(path)
    try stream.iterator().asScala.toVector finally stream.close()
  }

  "Report execution identity" should "separate repetitions of the same scenario independently of modeled time and seed" in {
    val first = ReportRunIdentity.create("same/scenario")
    val second = ReportRunIdentity.create("same/scenario")
    first.scenarioId shouldBe second.scenarioId
    first.simulationId should not be second.simulationId
    first.simulationId should fullyMatch regex "[A-Za-z0-9_-]+"
    ReportRunIdentity.create("").scenarioId shouldBe "sim"
  }

  it should "keep repeated runs and parallel file reporters separate for CSV, JSON and Parquet" in {
    val first = ReportRunIdentity.create("same-scenario")
    val second = ReportRunIdentity.create("same-scenario")
    val probe = TestProbe()
    for ((kind, clazz) <- Vector("csv" -> classOf[CsvReportData], "json" -> classOf[JsonReportData],
      "parquet" -> classOf[ParquetReportData]); run <- Vector(first, second)) {
      for (worker <- 1 to 2) {
        // Use the same reflective constructor as the production cluster router.
        val sink = system.actorOf(Props(clazz, probe.ref, start, run.simulationId, run.scenarioId))
        probe.send(sink, CommittedReportDelivery(run.simulationId, worker.toLong, event, probe.ref))
        probe.expectMsg(DeliveryAccepted(run.simulationId, worker.toLong))
        probe.send(sink, FlushRuntimeReports(worker.toLong, probe.ref))
        probe.expectMsg(RuntimeReportsFlushed(worker.toLong))
        probe.watch(sink)
        system.stop(sink)
        probe.expectTerminated(sink)
      }
      val output = files(directory.resolve(kind).resolve(run.simulationId))
      output should have size 2
      output.foreach { file =>
        kind match {
          case "csv" =>
            val rows = Files.readAllLines(file).asScala
            rows should have size 2
            rows.head should include ("scenario_id")
            rows(1) should include (run.simulationId)
            rows(1) should include (run.scenarioId)
          case "json" =>
            val rows = Files.readAllLines(file).asScala
            rows should have size 1
            val row = json.readTree(rows.head)
            row.path("simulation_id").asText() shouldBe run.simulationId
            row.path("scenario_id").asText() shouldBe run.scenarioId
          case "parquet" =>
            val reader = AvroParquetReader.builder[GenericRecord](new HadoopPath(file.toUri)).build()
            try {
              val row = reader.read()
              row.get("simulation_id").toString shouldBe run.simulationId
              row.get("scenario_id").toString shouldBe run.scenarioId
              reader.read() shouldBe null
            } finally reader.close()
        }
      }
    }
  }

  it should "retain the execution identity in manager Props across restarts" in {
    val first = ReportManager.props(null, null, start, "same-scenario")
    val second = ReportManager.props(null, null, start, "same-scenario")
    first.args.takeRight(2).head shouldBe "same-scenario"
    first.args.last should not be second.args.last
    first.withDispatcher("pekko.actor.io-dispatcher").args shouldBe first.args
    val managed = ReportManager.props(null, null, start, "same-scenario", Some("managed-run-123"))
    managed.args.last shouldBe "managed-run-123"
    an[IllegalArgumentException] should be thrownBy ReportRunIdentity.create("scenario", Some("../unsafe"))
    val prepare = org.htc.protobuf.core.entity.event.control.execution.PrepareSimulationEvent(
      configuration = "simulation.json", reportSimulationId = "managed-run-123")
    org.htc.protobuf.core.entity.event.control.execution.PrepareSimulationEvent.parseFrom(prepare.toByteArray) shouldBe prepare
  }

  it should "preserve scenario metadata and the original Kafka payload through serialization" in {
    val run = ReportRunIdentity.create("same-scenario")
    val report = SimulationReportEventAvro.fromReportEvent(event, run.simulationId).copy(scenarioId = run.scenarioId)
    val decoded = SimulationReportEventAvro.deserialize(SimulationReportEventAvro.serialize(report).get).get
    decoded shouldBe report
    json.readTree(decoded.dataJson).path("speed").asInt() shouldBe 10
    val legacy = JsonUtil.toJson(Map[String, Any]("simulationId" -> "old-run", "entityId" -> "1",
      "entityType" -> "", "tick" -> 0L, "lamportTick" -> 0L, "realTimeMs" -> 0L,
      "label" -> "sample", "dataJson" -> "{}"))
    SimulationReportEventAvro.deserialize(legacy.getBytes(UTF_8)).get.scenarioId shouldBe ""
  }

  it should "give ClickHouse flow and timing inserts separate IDs on every execution" in {
    assume(!sys.env.contains("HTC_CLICKHOUSE_HOST") && !sys.env.contains("HTC_CLICKHOUSE_PORT"),
      "requires the isolated HTTP test endpoint")
    val first = ReportRunIdentity.create("same-scenario")
    val second = ReportRunIdentity.create("same-scenario")
    val probe = TestProbe()
    for (run <- Vector(first, second)) {
      val sink = system.actorOf(Props(classOf[ClickHouseReportData], probe.ref, start,
        run.simulationId, run.scenarioId))
      val flow = event.copy(data = VehicleLinkFlowData("link-1", "enter", "vehicle-1", "Car", "static", 1))
      val timing = event.copy(label = "execution_timing_summary", data = Map("operation" -> "route"))
      for ((report, token) <- Vector(flow -> 1L, timing -> 2L)) {
        probe.send(sink, CommittedReportDelivery(run.simulationId, token, report, probe.ref))
        probe.expectMsg(DeliveryAccepted(run.simulationId, token))
      }
      probe.send(sink, FlushRuntimeReports(1L, probe.ref))
      probe.expectMsg(10.seconds, RuntimeReportsFlushed(1L))
      probe.watch(sink)
      system.stop(sink)
      probe.expectTerminated(sink)
    }
    val captured = requests.iterator().asScala.toVector
    for (table <- Vector("vehicle_link_events", "execution_timings")) {
      val rows = captured.filter { case (query, _) => query.contains(s".$table FORMAT JSONEachRow") }
        .map { case (_, body) => json.readTree(body) }
      rows.map(_.path("simulation_id").asText()).toSet shouldBe Set(first.simulationId, second.simulationId)
      rows.map(_.path("scenario_id").asText()).toSet shouldBe Set(first.scenarioId)
      captured.exists { case (_, sql) => sql.contains(s".$table ADD COLUMN IF NOT EXISTS scenario_id") } shouldBe true
    }
    captured.exists { case (_, sql) => sql.contains("TRUNCATE") || sql.contains("DELETE FROM") } shouldBe false
  }
}
