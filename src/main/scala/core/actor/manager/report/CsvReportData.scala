package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.ReportEvent

import org.apache.pekko.actor.ActorRef

import java.io.FileWriter
import java.nio.file.{ Files, Path, Paths }
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable

class CsvReportData(
  override val reportManager: ActorRef,
  override val startRealTime: LocalDateTime,
  val simulationId: String,
  val scenarioId: String
) extends ReportData(
      id = "csv-report-manager",
      reportManager = reportManager,
      startRealTime = startRealTime
    ) {

  private val prefix =
    Some(config.getString("htc.report-manager.csv.prefix")).getOrElse("simulation_")
  private val baseDirectory = Some(config.getString("htc.report-manager.csv.directory"))
    .getOrElse("/tmp/reports/csv")

  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
  private val effectiveStartTime = Option(startRealTime).getOrElse(LocalDateTime.now())
  private val simulationClock = core.util.SimulationClock.current
  private val timeBasedId = effectiveStartTime.format(dateFormatter)
  private val directory = s"$baseDirectory/$simulationId"

  private val batchSize = Some(config.getInt("htc.report-manager.csv.batch-size")).getOrElse(100)

  private val buffer = mutable.ListBuffer[ReportEvent]()

  private val fileName = s"${prefix}${timeBasedId}_${java.util.UUID.randomUUID()}_events.csv"
  private val filePath = s"$directory/$fileName"

  override def onReport(event: ReportEvent): Unit = {
    buffer += event
    if (buffer.size >= batchSize) {
      flushBuffer()
    }
  }

  private def flushBuffer(): Unit = {
    if (buffer.isEmpty) return

    mkdir(directory)
    val fileExists = Files.exists(Paths.get(filePath))

    try {
      val writer = new FileWriter(filePath, true)

      if (!fileExists || Files.size(Paths.get(filePath)) == 0) {
        writer.write("entity_id,tick,real_time,lamport_tick,event_type,simulation_id,data,node,from_node,message_type,from_type,scenario_id\n")
      }

      for (report <- buffer) {
        val cleanData =
          report.data.toString.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")
        writer.write(
          s"${report.entityId},${report.tick},${simulationClock.at(effectiveStartTime, report.tick)},${report.lamportTick},${report.label},${simulationId},\"$cleanData\",${core.cluster.NodeIdentity.originLabel(report.node)},${core.cluster.NodeIdentity.originLabel(report.fromNode)},${report.messageType},${report.fromType},\"${scenarioId.replace("\"", "\"\"")}\"\n"
        )
      }
      writer.close()
      logInfo(s"Flushed ${buffer.size} events to $filePath")
      buffer.clear()
    } catch {
      case e: Exception =>
        logError(s"Failed to write CSV report to file: ${e.getMessage}", e)
        throw e
    }
  }

  private def mkdir(directory: String): Unit = {
    val dirPath: Path = Paths.get(directory)
    if (!Files.exists(dirPath)) {
      Files.createDirectories(dirPath)
    }
  }

  override protected def flushCommittedReports(): scala.concurrent.Future[Unit] = {
    flushBuffer()
    val path = Paths.get(filePath)
    if (Files.exists(path)) {
      val channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)
      try channel.force(true) finally channel.close()
    }
    scala.concurrent.Future.successful(())
  }

  override def postStop(): Unit =
    if (buffer.nonEmpty) {
      flushBuffer()
    }
}
