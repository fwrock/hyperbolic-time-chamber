package org.interscity.htc.avro.simulation

import org.interscity.htc.core.entity.event.control.report.ReportEvent
import org.interscity.htc.core.util.JsonUtil

import scala.util.Try

/** Manual implementation of SimulationReportEvent — mirrors the Avro schema in
  * src/main/avro/simulation/simulation_report_event.avsc without requiring the sbt-avro plugin.
  */
case class SimulationReportEvent(
  simulationId: String,
  entityId: String,
  entityType: Option[String] = None,
  tick: Long,
  lamportTick: Long,
  realTimeMs: Long,
  label: Option[String] = None,
  dataJson: String
)

object SimulationReportEventAvro {

  private case class SimulationReportEventWire(
    simulationId: String,
    entityId: String,
    entityType: String,
    tick: Long,
    lamportTick: Long,
    realTimeMs: Long,
    label: String,
    dataJson: String
  )

  def serialize(event: SimulationReportEvent): Try[Array[Byte]] =
    Try {
      val wire = SimulationReportEventWire(
        simulationId = event.simulationId,
        entityId     = event.entityId,
        entityType   = event.entityType.getOrElse(""),
        tick         = event.tick,
        lamportTick  = event.lamportTick,
        realTimeMs   = event.realTimeMs,
        label        = event.label.getOrElse(""),
        dataJson     = event.dataJson
      )
      JsonUtil.toJson(wire).getBytes("UTF-8")
    }

  def deserialize(data: Array[Byte]): Try[SimulationReportEvent] =
    Try {
      val json = new String(data, "UTF-8")
      val wire = JsonUtil.fromJson[SimulationReportEventWire](json)
      SimulationReportEvent(
        simulationId = wire.simulationId,
        entityId     = wire.entityId,
        entityType   = if (wire.entityType.isEmpty) None else Some(wire.entityType),
        tick         = wire.tick,
        lamportTick  = wire.lamportTick,
        realTimeMs   = wire.realTimeMs,
        label        = if (wire.label.isEmpty) None else Some(wire.label),
        dataJson     = wire.dataJson
      )
    }

  def fromReportEvent(event: ReportEvent, simulationId: String): SimulationReportEvent = {
    val dataJson =
      try JsonUtil.toJson(event.data)
      catch { case _: Exception => event.data.toString }
    SimulationReportEvent(
      simulationId = simulationId,
      entityId     = event.entityId,
      entityType   = None,
      tick         = event.tick,
      lamportTick  = event.lamportTick,
      realTimeMs   = System.currentTimeMillis(),
      label        = Option(event.label),
      dataJson     = dataJson
    )
  }
}
