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
  override val startRealTime: LocalDateTime
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
  private val timeBasedId = effectiveStartTime.format(dateFormatter)

  private lazy val simulationId: String = {
    val simulationConfigId =
      try {
        val simulationConfig = core.util.SimulationUtil.loadSimulationConfig()
        simulationConfig.id
      } catch {
        case _: Exception => None
      }

    val envSimId = sys.env.get("HTC_SIMULATION_ID")

    val configSimId =
      try
        Some(config.getString("htc.simulation.id"))
      catch {
        case _: Exception => None
      }

    simulationConfigId
      .orElse(envSimId)
      .orElse(configSimId)
      .getOrElse {
        val simulationName =
          try
            core.util.SimulationUtil.loadSimulationConfig().name.replaceAll("[^a-zA-Z0-9_-]", "_")
          catch {
            case _: Exception => "sim"
          }

        try
          core.actor.manager.RandomSeedManager.deterministicSimulationId(simulationName)
        catch {
          case _: Exception =>
            s"${simulationName}_${timeBasedId}"
        }
      }
  }

  private val directory = s"$baseDirectory/$simulationId"

  private val batchSize = Some(config.getInt("htc.report-manager.csv.batch-size")).getOrElse(100)

  private val buffer = mutable.ListBuffer[ReportEvent]()

  private val fileName = s"${prefix}${timeBasedId}_events.csv"
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
        writer.write("entity_id,tick,real_time,lamport_tick,event_type,simulation_id,data\n")
      }

      for (report <- buffer) {
        val cleanData =
          report.data.toString.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")
        writer.write(
          s"${report.entityId},${report.tick},${startRealTime
              .plusSeconds(report.tick)},${report.lamportTick},${report.label},${simulationId},\"$cleanData\"\n"
        )
      }
      writer.close()
      logInfo(s"Flushed ${buffer.size} events to $filePath")
      buffer.clear()
    } catch {
      case e: Exception =>
        logError(s"Failed to write CSV report to file: ${e.getMessage}", e)
    }
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
