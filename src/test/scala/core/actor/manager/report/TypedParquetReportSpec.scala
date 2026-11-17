package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.ReportEvent
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.avro.AvroParquetReader
import org.apache.pekko.actor.{ ActorSystem, Props }
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.time.LocalDateTime
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class TypedParquetReportSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val directory = Files.createTempDirectory("htc-typed-parquet-")
  private implicit val system: ActorSystem = ActorSystem(
    "TypedParquetReportSpec",
    ConfigFactory
      .parseString(s"""
      pekko.actor.provider=local
      pekko.actor.fail-mixed-versions=off
      htc.report-manager.parquet.directory="$directory"
      htc.report-manager.parquet.payload-encoding="typed-capacity-v1"
    """)
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  "Typed Parquet reports" should "persist typed audits and lossless JSON fallbacks in the same closed file" in {
    val probe = TestProbe()
    val sink = system.actorOf(
      Props(
        classOf[ParquetReportData],
        probe.ref,
        LocalDateTime.of(2026, 1, 1, 0, 0),
        "typed-run",
        "scenario"
      ).withDispatcher("pekko.actor.io-dispatcher")
    )
    val payload = Map[String, Any](
      "vehicle_id" -> 9007199254740993L,
      "link_id" -> 2L,
      "tick" -> 10L,
      "physical_occupancy" -> 1,
      "storage_vehicles" -> 3,
      "flow_credit" -> 0.25,
      "flow_debit" -> false,
      "origin_at_end" -> false,
      "destination_arrival" -> true
    )
    val event = ReportEvent("1", 10L, 0L, data = payload, label = "meso_capacity_released")
    probe.send(
      sink,
      CommittedReportBatchDelivery(
        "run",
        1L,
        Vector(event, event.copy(data = payload + ("plugin" -> Map("nested" -> 9)))),
        probe.ref
      )
    )
    probe.expectMsg(15.seconds, DeliveryAccepted("run", 1L))
    probe.send(sink, FlushRuntimeReports(2L, probe.ref))
    probe.expectMsg(15.seconds, RuntimeReportsFlushed(2L))
    val stream = Files.list(directory.resolve("typed-run"))
    val file =
      try stream.iterator().asScala.toVector.head
      finally stream.close()
    val reader = AvroParquetReader.builder[GenericRecord](new HadoopPath(file.toUri)).build()
    try {
      val typed = reader.read()
      typed.get("data") shouldBe null
      val values = typed.get("capacity_data_v1").asInstanceOf[GenericRecord]
      values.get("vehicle_id").asInstanceOf[Long] shouldBe 9007199254740993L
      values.get("destination_arrival").asInstanceOf[Boolean] shouldBe true
      val generic = reader.read()
      generic.get("capacity_data_v1") shouldBe null
      generic.get("data").toString should include("nested")
      reader.read() shouldBe null
    } finally reader.close()
  }
}
