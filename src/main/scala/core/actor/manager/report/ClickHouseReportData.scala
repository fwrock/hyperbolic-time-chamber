package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.ReportEvent
import model.mobility.entity.event.data.VehicleLinkFlowData
import system.database.clickhouse.ClickHouseClientManager

import org.apache.pekko.actor.ActorRef

import java.time.LocalDateTime
import scala.collection.mutable

/** Reporter actor that streams vehicle link flow events to ClickHouse.
  *
  * Each actor instance maintains a local buffer and flushes in bulk via the
  * ClickHouse HTTP API (JSONEachRow format). Running on the io-dispatcher means
  * the blocking HTTP call never blocks the simulation actor pool.
  *
  * Fail-safe: if ClickHouse is unreachable at startup or during a flush, a
  * warning is logged and the events are dropped. The simulation continues
  * unaffected.
  */
class ClickHouseReportData(
  override val reportManager: ActorRef,
  override val startRealTime: LocalDateTime
) extends ReportData(
      id = "clickhouse-report-data",
      reportManager = reportManager,
      startRealTime = startRealTime
    ) {
  
  private val host     = sys.env.getOrElse("HTC_CLICKHOUSE_HOST",
    try config.getString("htc.report-manager.clickhouse.host") catch { case _: Exception => "localhost" })
  private val port     = sys.env.get("HTC_CLICKHOUSE_PORT")
    .map(_.toInt)
    .getOrElse(try config.getInt("htc.report-manager.clickhouse.port") catch { case _: Exception => 8123 })
  private val database = sys.env.getOrElse("HTC_CLICKHOUSE_DATABASE",
    try config.getString("htc.report-manager.clickhouse.database") catch { case _: Exception => "htc" })
  private val username = sys.env.getOrElse("HTC_CLICKHOUSE_USER",
    try config.getString("htc.report-manager.clickhouse.username") catch { case _: Exception => "default" })
  private val password = sys.env.getOrElse("HTC_CLICKHOUSE_PASSWORD",
    try config.getString("htc.report-manager.clickhouse.password") catch { case _: Exception => "" })
  private val batchSize =
    try config.getInt("htc.report-manager.clickhouse.batch-size") catch { case _: Exception => 50000 }
  
  private lazy val simulationId: String = {
    val fromConfig =
      try Some(core.util.SimulationUtil.loadSimulationConfig().id).flatten
      catch { case _: Exception => None }
    val fromEnv    = sys.env.get("HTC_SIMULATION_ID")
    val fromConf   =
      try Some(config.getString("htc.simulation.id"))
      catch { case _: Exception => None }
    fromConfig.orElse(fromEnv).orElse(fromConf).getOrElse("sim")
  }
  
  private val ch = new ClickHouseClientManager(host, port, database, username, password)

  /** Flag: false if CH was unreachable at startup (skip all flushes silently). */
  private var chAvailable: Boolean = true

  override def preStart(): Unit = {
    super.preStart()
    ch.createSchemaIfNeeded() match {
      case Right(_)    => logInfo(s"ClickHouse schema ready at $host:$port/$database")
      case Left(error) =>
        chAvailable = false
        logWarn(s"ClickHouse unavailable ($error). Flow reporting disabled for this actor.")
    }
  }
  
  private val buffer = mutable.ArrayBuffer.empty[String]
  
  override def onReport(event: ReportEvent): Unit =
    event.data match {
      case flow: VehicleLinkFlowData =>
        buffer += toJsonLine(event, flow)
        if (buffer.size >= batchSize) flush()
      case _ =>
    }

  override def postStop(): Unit = {
    if (buffer.nonEmpty) flush()
  }
  
  private def flush(): Unit = {
    if (!chAvailable || buffer.isEmpty) {
      buffer.clear()
      return
    }
    val rows = buffer.toSeq
    buffer.clear()
    ch.insertBatch(rows) match {
      case Right(_) => // success
      case Left(err) =>
        logWarn(s"ClickHouse flush failed ($err). Dropped ${rows.size} rows.")
    }
  }

  /** Converts a flow event to a ClickHouse-compatible JSON object string. */
  private def toJsonLine(event: ReportEvent, flow: VehicleLinkFlowData): String = {
    val sb = new java.lang.StringBuilder(256)
    sb.append("{\"simulation_id\":\"").append(escape(simulationId)).append('"')
    sb.append(",\"tick\":").append(event.tick)
    sb.append(",\"real_time_ms\":").append(System.currentTimeMillis())
    sb.append(",\"link_id\":\"").append(escape(flow.linkId)).append('"')
    sb.append(",\"event_type\":\"").append(flow.eventType).append('"')
    sb.append(",\"vehicle_id\":\"").append(escape(flow.vehicleId)).append('"')
    sb.append(",\"actor_type\":\"").append(escape(flow.actorType)).append('"')
    sb.append(",\"actor_creation_type\":\"").append(escape(flow.actorCreationType)).append('"')
    sb.append(",\"vehicle_count_on_link\":").append(flow.vehicleCountOnLink)
    sb.append('}')
    sb.toString
  }

  /** Minimal JSON string escaping (backslash and double-quote). */
  private def escape(s: String): String =
    if (s == null) ""
    else s.replace("\\", "\\\\").replace("\"", "\\\"")
}
