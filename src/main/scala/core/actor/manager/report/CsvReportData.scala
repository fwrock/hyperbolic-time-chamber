package org.interscity.htc
package core.actor.manager.report

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.control.report.ReportEvent

import java.io.{BufferedWriter, FileWriter}
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable

class CsvReportData(override val reportManager: ActorRef)
    extends ReportData(
      id = "csv-report-manager",
      reportManager = reportManager
    ) {

  private val prefix = Some(config.getString("htc.report-manager.csv.prefix")).getOrElse("report_")
  private val directory = Some(config.getString("htc.report-manager.csv.batch-size")).getOrElse(s"/tmp/reports/csv/${System.currentTimeMillis()}")
  private val batchSize = Some(config.getInt("htc.report-manager.csv.batch-size")).getOrElse(1000)

  private val buffer = mutable.ListBuffer[ReportEvent]()
  private var fileWriter: Option[BufferedWriter] = None

  override def onReport(event: ReportEvent): Unit = {
    buffer += event
    if (buffer.size >= batchSize) {
      flushBuffer()
    }
  }

  private def flushBuffer(): Unit = {
    mkdir(directory)
    val filePath = s"$directory/$prefix${System.currentTimeMillis()}.csv"
    val writer = new BufferedWriter(new FileWriter(filePath, true))
    buffer.foreach {
      report =>
        writer.write(s"${report.entityId},${report.timestamp},${report.tick}${report.lamportTick},${report.data}\n")
    }
    writer.close()
    buffer.clear()
  }

  private def mkdir(directory: String): Unit = {
    val dirPath: Path = Paths.get(directory)
    if (!Files.exists(dirPath)) {
      Files.createDirectories(dirPath)
    }
  }

  override def postStop(): Unit =
    if (buffer.nonEmpty) {
      flushBuffer()
    }
}
