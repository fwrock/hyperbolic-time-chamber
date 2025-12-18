package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.ReportEvent

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.util.JsonUtil

import java.io.{ BufferedWriter, FileWriter }
import java.nio.file.{ Files, Path, Paths }
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable

class JsonReportData(
  override val reportManager: ActorRef,
  override val startRealTime: LocalDateTime
) extends ReportData(
      id = "json-report-manager",
      reportManager = reportManager,
      startRealTime = startRealTime
    ) {

  private val prefix =
    Some(config.getString("htc.report-manager.json.prefix")).getOrElse("simulation_")
  private val baseDirectory = Some(config.getString("htc.report-manager.json.directory"))
    .getOrElse("/tmp/reports/json")

  // Create readable directory name with timestamp
  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
  private val timeBasedId = startRealTime.format(dateFormatter)

  // Generate simulation ID using same logic as CassandraReportData
  private lazy val simulationId: String = {
    // 1. Try simulation config
    val simulationConfigId =
      try {
        val simulationConfig = core.util.SimulationUtil.loadSimulationConfig()
        simulationConfig.id
      } catch {
        case _: Exception => None
      }

    // 2. Try environment variable
    val envSimId = sys.env.get("HTC_SIMULATION_ID")

    // 3. Try application.conf
    val configSimId =
      try
        Some(config.getString("htc.simulation.id"))
      catch {
        case _: Exception => None
      }

    // 4. Generate fallback ID
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

  private val batchSize = Some(config.getInt("htc.report-manager.json.batch-size")).getOrElse(100)

  private val buffer = mutable.ListBuffer[ReportEvent]()
  private var fileWriter: Option[BufferedWriter] = None

  // FIX: Each JsonReportData instance must write to a UNIQUE file
  // Multiple instances writing to the same file causes corrupted JSONL
  // Solution: Include actor's unique ID in filename
  private val actorUniqueId = java.util.UUID.randomUUID().toString.take(8)
  private val fileName = s"${prefix}${timeBasedId}_${actorUniqueId}_events.jsonl"
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

    try {
      val writer = new BufferedWriter(new FileWriter(filePath, true))
      buffer.foreach {
        report =>
          val jsonData = Map(
            "tick" -> report.tick,
            "real_time" -> System.currentTimeMillis(),
            "simulation_id" -> simulationId,
            "event_type" -> report.label,
            "data" -> report.data
          )
          writer.write(JsonUtil.toJson(jsonData))
          writer.newLine()
      }
      writer.close()
      logInfo(s"Flushed ${buffer.size} events to $filePath")
      buffer.clear()
    } catch {
      case e: Exception =>
        logError(s"Failed to write report to file: ${e.getMessage}", e)
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
