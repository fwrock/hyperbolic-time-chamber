package org.interscity.htc.avro.bus

import org.interscity.htc.core.util.JsonUtil

import scala.util.{ Failure, Success, Try }

/** JSON-based serialization utilities for HtcReadyEvent.
  *
  * Uses JSON (same approach as DynamicLinkCostAvro) until the sbt-avro plugin issues are resolved.
  */
object HtcReadyEventAvro {

  /** Wire-safe intermediate for Jackson serialization (all fields as strings to avoid sealed-trait
    * serialization issues with the default Jackson mapper).
    */
  case class HtcReadyEventWire(
    eventId: String,
    entityId: String,
    entityType: String,
    eventKind: String,
    payload: String,
    sourceTimestamp: Long,
    simulationTick: Option[Long] = None,
    htcdlModelId: Option[String] = None,
    anomalyType: Option[String] = None
  )

  def serialize(event: HtcReadyEvent): Try[Array[Byte]] =
    Try {
      val wire = toWire(event)
      JsonUtil.toJson(wire).getBytes("UTF-8")
    }

  def deserialize(data: Array[Byte]): Try[HtcReadyEvent] =
    Try {
      val json = new String(data, "UTF-8")
      val wire = JsonUtil.fromJson[HtcReadyEventWire](json)
      fromWire(wire)
    }

  def toInternal(event: HtcReadyEvent): Map[String, Any] =
    Map(
      "eventId"         -> event.eventId,
      "entityId"        -> event.entityId,
      "entityType"      -> event.entityType.toString,
      "eventKind"       -> event.eventKind.toString,
      "payload"         -> event.payload,
      "sourceTimestamp" -> event.sourceTimestamp,
      "simulationTick"  -> event.simulationTick.orNull,
      "htcdlModelId"    -> event.htcdlModelId.orNull,
      "anomalyType"     -> event.anomalyType.map(_.toString).orNull
    )

  def fromInternal(data: Map[String, Any]): Try[HtcReadyEvent] =
    Try {
      HtcReadyEvent(
        eventId         = data("eventId").toString,
        entityId        = data("entityId").toString,
        entityType      = EntityType.fromString(data("entityType").toString),
        eventKind       = EventKind.fromString(data("eventKind").toString),
        payload         = data("payload").toString,
        sourceTimestamp = data("sourceTimestamp").asInstanceOf[Long],
        simulationTick  = data.get("simulationTick").flatMap(v => Option(v)).map(_.asInstanceOf[Long]),
        htcdlModelId    = data.get("htcdlModelId").flatMap(v => Option(v)).map(_.toString),
        anomalyType     = data.get("anomalyType").flatMap(v => Option(v)).map(v => AnomalyType.fromString(v.toString))
      )
    }

  private def toWire(event: HtcReadyEvent): HtcReadyEventWire =
    HtcReadyEventWire(
      eventId         = event.eventId,
      entityId        = event.entityId,
      entityType      = event.entityType.toString,
      eventKind       = event.eventKind.toString,
      payload         = event.payload,
      sourceTimestamp = event.sourceTimestamp,
      simulationTick  = event.simulationTick,
      htcdlModelId    = event.htcdlModelId,
      anomalyType     = event.anomalyType.map(_.toString)
    )

  private def fromWire(wire: HtcReadyEventWire): HtcReadyEvent =
    HtcReadyEvent(
      eventId         = wire.eventId,
      entityId        = wire.entityId,
      entityType      = EntityType.fromString(wire.entityType),
      eventKind       = EventKind.fromString(wire.eventKind),
      payload         = wire.payload,
      sourceTimestamp = wire.sourceTimestamp,
      simulationTick  = wire.simulationTick,
      htcdlModelId    = wire.htcdlModelId,
      anomalyType     = wire.anomalyType.map(AnomalyType.fromString)
    )
}
