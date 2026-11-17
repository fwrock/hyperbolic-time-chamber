package org.interscity.htc
package core.actor.manager.report

import org.apache.avro.generic.{ GenericDatumReader, GenericDatumWriter, GenericRecord }
import org.apache.avro.io.{ DecoderFactory, EncoderFactory }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayOutputStream

class ParquetCapacityPayloadSpec extends AnyFlatSpec with Matchers {
  private val values: Map[String, Any] = Map(
    "vehicle_id" -> 9007199254740993L,
    "link_id" -> 2L,
    "tick" -> 10L,
    "physical_occupancy" -> 3,
    "storage_vehicles" -> 4,
    "flow_credit" -> 0.125,
    "flow_debit" -> true,
    "origin_at_end" -> false,
    "destination_arrival" -> false
  )

  "Typed capacity payloads" should "preserve integer identities beyond double precision through independent binary codecs" in {
    val record = ParquetCapacityPayload.encode("meso_capacity_admitted", values).get
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    new GenericDatumWriter[GenericRecord](record.getSchema).write(record, encoder)
    encoder.flush()
    val copy = new GenericDatumReader[GenericRecord](record.getSchema)
      .read(null, DecoderFactory.get().binaryDecoder(bytes.toByteArray, null))
    copy.get("vehicle_id").asInstanceOf[Long] shouldBe 9007199254740993L
    copy.get("flow_credit").asInstanceOf[Double] shouldBe 0.125
    copy.get("physical_occupancy").asInstanceOf[Long] shouldBe 3L
    copy.get("flow_debit").asInstanceOf[Boolean] shouldBe true
  }
  it should "fall back for extended, missing, malformed or unrelated events" in {
    ParquetCapacityPayload.encode("other", values) shouldBe None
    ParquetCapacityPayload.encode(
      "meso_capacity_admitted",
      values + ("plugin" -> "keep")
    ) shouldBe None
    ParquetCapacityPayload.encode("meso_capacity_admitted", values - "link_id") shouldBe None
    ParquetCapacityPayload.encode(
      "meso_capacity_admitted",
      values.updated("vehicle_id", 2.5)
    ) shouldBe None
  }
}
