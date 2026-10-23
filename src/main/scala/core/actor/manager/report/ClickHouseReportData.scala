package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.ReportEvent
import model.mobility.entity.event.data.VehicleLinkFlowData
import system.database.clickhouse.ClickHouseClientManager

import org.apache.pekko.actor.ActorRef

import java.time.LocalDateTime
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

/** Reporter actor that streams vehicle link flow events to ClickHouse.
  *
  * Each actor instance maintains a local buffer and flushes in bulk via the ClickHouse HTTP API
  * (JSONEachRow format). Running on the io-dispatcher means the blocking HTTP call never blocks the
  * simulation actor pool.
  *
  * Legacy reporting is best effort. Accounted commitment retains schema/write failures and
  * acknowledges the drain only after ClickHouse confirms the writes.
  */
class ClickHouseReportData(
  override val reportManager: ActorRef,
  override val startRealTime: LocalDateTime
) extends ReportData(
      id = "clickhouse-report-data",
      reportManager = reportManager,
      startRealTime = startRealTime
    ) {

  private val host = sys.env.getOrElse(
    "HTC_CLICKHOUSE_HOST",
    try config.getString("htc.report-manager.clickhouse.host")
    catch { case _: Exception => "localhost" }
  )
  private val port = sys.env
    .get("HTC_CLICKHOUSE_PORT")
    .map(_.toInt)
    .getOrElse(
      try config.getInt("htc.report-manager.clickhouse.port")
      catch { case _: Exception => 8123 }
    )
  private val database = sys.env.getOrElse(
    "HTC_CLICKHOUSE_DATABASE",
    try config.getString("htc.report-manager.clickhouse.database")
    catch { case _: Exception => "htc" }
  )
  private val username = sys.env.getOrElse(
    "HTC_CLICKHOUSE_USER",
    try config.getString("htc.report-manager.clickhouse.username")
    catch { case _: Exception => "default" }
  )
  private val password = sys.env.getOrElse(
    "HTC_CLICKHOUSE_PASSWORD",
    try config.getString("htc.report-manager.clickhouse.password")
    catch { case _: Exception => "" }
  )
  private val batchSize =
    try config.getInt("htc.report-manager.clickhouse.batch-size")
    catch { case _: Exception => 50000 }

  private lazy val simulationId: String = {
    val fromConfig =
      try Some(core.util.SimulationUtil.loadSimulationConfig().id).flatten
      catch { case _: Exception => None }
    val fromEnv = sys.env.get("HTC_SIMULATION_ID")
    val fromConf =
      try Some(config.getString("htc.simulation.id"))
      catch { case _: Exception => None }
    fromConfig.orElse(fromEnv).orElse(fromConf).getOrElse("sim")
  }

  private val ch = new ClickHouseClientManager(host, port, database, username, password)

  private implicit val io: ExecutionContext = context.system.dispatchers.lookup("pekko.actor.io-dispatcher")
  private lazy val schemaReady: Future[Unit] = Future {
    ch.createSchemaIfNeeded() match {
      case Right(_) => ()
      case Left(error) => throw new IllegalStateException(s"ClickHouse schema unavailable: $error")
    }
  }
  private var pendingWrites: Future[Unit] = Future.successful(())
  private var strictCommit = false

  // Pre-sized to batchSize to avoid the doubling-resize cycle when filling the buffer.
  private val buffer = new mutable.ArrayBuffer[String](batchSize)

  override def onReport(event: ReportEvent): Unit =
    event.data match {
      case flow: VehicleLinkFlowData =>
        buffer += toJsonLine(event, flow)
        if (buffer.size >= batchSize) flush()
      case _ =>
    }

  override protected def onCommittedReport(event: ReportEvent): Unit = {
    require(event.data.isInstanceOf[VehicleLinkFlowData], "unsupported ClickHouse report payload")
    strictCommit = true
    onReport(event)
  }

  override protected def flushCommittedReports(): Future[Unit] = {
    strictCommit = true
    flush()
    pendingWrites
  }

  override def postStop(): Unit = { flush(); () }

  private def flush(): Future[Unit] = {
    if (buffer.isEmpty) return pendingWrites
    val rows = buffer.toVector
    buffer.clear()
    val strict = strictCommit
    val write = pendingWrites.flatMap(_ => schemaReady).flatMap { _ => Future {
      ch.insertBatch(rows) match {
        case Right(_) => ()
        case Left(error) => throw new IllegalStateException(s"ClickHouse write failed: $error")
      }
    }}
    pendingWrites = if (strict) write else write.recover { case error => logWarn(s"ClickHouse reporting failed: ${error.getMessage}") }
    pendingWrites
  }

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
