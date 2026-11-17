package org.interscity.htc
package core.actor.manager.report

import org.apache.avro.Schema
import org.apache.avro.generic.{ GenericData, GenericRecord }

/** Lossless typed encoding for the frequent, fixed-shape capacity audit records. Unknown fields or
  * types retain the generic JSON path rather than silently discarding information.
  */
private[report] object ParquetCapacityPayload {
  private val longs =
    Vector("vehicle_id", "link_id", "tick", "physical_occupancy", "storage_vehicles")
  private val booleans = Vector("flow_debit", "origin_at_end", "destination_arrival")
  private val keys = (longs ++ booleans :+ "flow_credit").toSet
  val schema: Schema = new Schema.Parser().parse(
    s"""{"type":"record","name":"CapacityReportDataV1","fields":[${(longs.map(
        k => s"""{"name":"$k","type":"long"}"""
      ) ++
        booleans.map(
          k => s"""{"name":"$k","type":"boolean"}"""
        ) :+
        """{"name":"flow_credit","type":"double"}""").mkString(",")}]}"""
  )

  /** Only exact capacity payloads qualify; plugin extensions remain representable as JSON. */
  def encode(label: String, data: Any): Option[GenericRecord] = data match {
    case values: scala.collection.Map[?, ?]
        if (label == "meso_capacity_admitted" || label == "meso_capacity_released") &&
          values.keySet == keys =>
      val fields = values.asInstanceOf[scala.collection.Map[String, Any]]
      def integral(value: Any): Boolean = value match {
        case _: Long | _: Int | _: Short | _: Byte => true
        case _                                     => false
      }
      val valid = longs.forall(
        k => integral(fields(k))
      ) &&
        booleans.forall(
          k => fields(k).isInstanceOf[Boolean]
        ) &&
        fields("flow_credit").isInstanceOf[Double] &&
        fields("flow_credit").asInstanceOf[Double].isFinite
      if (!valid) None
      else {
        val record = new GenericData.Record(schema)
        longs.foreach(
          k => record.put(k, fields(k).asInstanceOf[Number].longValue())
        )
        booleans.foreach(
          k => record.put(k, fields(k))
        )
        record.put("flow_credit", fields("flow_credit"))
        Some(record)
      }
    case _ => None
  }
}
