package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.ReportEvent
import core.util.JsonUtil

import org.apache.avro.Schema
import org.apache.avro.generic.{ GenericData, GenericRecord }
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.column.ParquetProperties
import org.apache.pekko.actor.ActorRef

import java.nio.file.{ Files, Path, Paths }
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable

/** Report writer that persists simulation events as Apache Parquet files.
  *
  * Each actor instance owns a single Parquet file (identified by a UUID suffix)
  * and keeps the writer open for the lifetime of the actor — identical to the
  * JsonReportData pattern. The writer is closed (and the Parquet footer is
  * written) in postStop(), ensuring readable files even on graceful shutdown.
  *
  * Compression codec is configurable via `htc.report-manager.parquet.compression`:
  *   snappy (default) — fast, moderate ratio; good for GCS / HDFS
  *   zstd             — better ratio, tunable level; good for long-term storage
  *   gzip             — maximum ratio, slow; included for compatibility
  *   uncompressed     — no compression, maximum write throughput
  *
  * Schema (flat, queryable with Spark / DuckDB / AWS Athena):
  *   entity_id     STRING
  *   tick          INT64
  *   real_time_ms  INT64
  *   lamport_tick  INT64
  *   event_type    STRING (nullable)
  *   simulation_id STRING
  *   data          STRING (nullable, JSON-encoded event payload)
  */
class ParquetReportData(
  override val reportManager: ActorRef,
  override val startRealTime: LocalDateTime
) extends ReportData(
      id = "parquet-report-data",
      reportManager = reportManager,
      startRealTime = startRealTime
    ) {


  private val SCHEMA_JSON =
    """{
      |  "type":      "record",
      |  "name":      "SimulationEvent",
      |  "namespace": "org.interscity.htc",
      |  "fields": [
      |    {"name": "entity_id",     "type": "string"},
      |    {"name": "tick",          "type": "long"},
      |    {"name": "real_time_ms",  "type": "long"},
      |    {"name": "lamport_tick",  "type": "long"},
      |    {"name": "event_type",    "type": ["null", "string"], "default": null},
      |    {"name": "simulation_id", "type": "string"},
      |    {"name": "data",          "type": ["null", "string"], "default": null}
      |  ]
      |}""".stripMargin

  private val SCHEMA = new Schema.Parser().parse(SCHEMA_JSON)
  
  private val prefix =
    try config.getString("htc.report-manager.parquet.prefix")
    catch { case _: Exception => "htc_simulation_" }

  private val baseDirectory =
    try config.getString("htc.report-manager.parquet.directory")
    catch { case _: Exception => "/tmp/reports/parquet" }

  private val compressionCodecName: String =
    try config.getString("htc.report-manager.parquet.compression")
    catch { case _: Exception => "snappy" }

  private val batchSize: Int =
    try config.getInt("htc.report-manager.parquet.batch-size")
    catch { case _: Exception => 10000 }

  private val rowGroupBytes: Long =
    try config.getLong("htc.report-manager.parquet.row-group-size-mb") * 1024L * 1024L
    catch { case _: Exception => 16L * 1024 * 1024 }

  private val zstdLevel: Int =
    try config.getInt("htc.report-manager.parquet.zstd-level")
    catch { case _: Exception => 6 }


  private val codec: CompressionCodecName = compressionCodecName match {
    case "snappy"        => CompressionCodecName.SNAPPY
    case "zstd"          => CompressionCodecName.ZSTD
    case "gzip"          => CompressionCodecName.GZIP
    case "uncompressed"  => CompressionCodecName.UNCOMPRESSED
    case other =>
      logWarn(s"Unknown Parquet compression codec '$other', falling back to SNAPPY")
      CompressionCodecName.SNAPPY
  }
  

  private val dateFormatter      = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
  private val effectiveStartTime = Option(startRealTime).getOrElse(LocalDateTime.now())
  private val timeBasedId        = effectiveStartTime.format(dateFormatter)

  private val extension: String = compressionCodecName match {
    case "snappy"        => ".snappy.parquet"
    case "zstd"          => ".zstd.parquet"
    case "gzip"          => ".gz.parquet"
    case _               => ".parquet"
  }

  private lazy val simulationId: String = {
    val fromSimConfig =
      try Some(core.util.SimulationUtil.loadSimulationConfig()).flatMap(_.id)
      catch { case _: Exception => None }
    val fromEnv  = sys.env.get("HTC_SIMULATION_ID")
    val fromConf =
      try Some(config.getString("htc.simulation.id"))
      catch { case _: Exception => None }

    fromSimConfig.orElse(fromEnv).orElse(fromConf).getOrElse {
      val name =
        try
          core.util.SimulationUtil.loadSimulationConfig().name.replaceAll("[^a-zA-Z0-9_-]", "_")
        catch { case _: Exception => "sim" }
      try core.actor.manager.RandomSeedManager.deterministicSimulationId(name)
      catch { case _: Exception => s"${name}_${timeBasedId}" }
    }
  }

  private lazy val directory = s"$baseDirectory/$simulationId"

  private def newFilePath(): String = {
    val freshId = java.util.UUID.randomUUID().toString.take(8)
    s"$directory/${prefix}${timeBasedId}_${freshId}_events${extension}"
  }


  private val buffer                                = mutable.ArrayBuffer.empty[ReportEvent]
  private var writer: ParquetWriter[GenericRecord]  = _
  private var currentFilePath: String               = _
  private var flushCount: Long                      = 0L

  private def getOrCreateWriter(): ParquetWriter[GenericRecord] = {
    if (writer == null) {
      mkdir(directory)
      currentFilePath = newFilePath()
      val outputFile = new LocalOutputFile(Paths.get(currentFilePath))

      val hadoopConf = new Configuration(false)
      hadoopConf.setInt("parquet.compression.codec.zstd.level", zstdLevel)

      writer = AvroParquetWriter
        .builder[GenericRecord](outputFile)
        .withConf(hadoopConf)
        .withSchema(SCHEMA)
        .withDataModel(GenericData.get())
        .withCompressionCodec(codec)
        .withRowGroupSize(rowGroupBytes)
        .withPageSize(256 * 1024)
        .withWriterVersion(ParquetProperties.WriterVersion.PARQUET_2_0)
        .withDictionaryEncoding(true)
        .withDictionaryEncoding("data", false)
        .withPageWriteChecksumEnabled(true)
        .build()
      logInfo(s"Opened Parquet writer: $currentFilePath (codec=$compressionCodecName, version=PARQUET_2_0)")
    }
    writer
  }

  private def closeWriter(): Unit =
    if (writer != null) {
      try { writer.close() }
      catch { case e: Exception => logError(s"Error closing Parquet writer: ${e.getMessage}", e) }
      writer = null
    }
  
  override def onReport(event: ReportEvent): Unit = {
    buffer += event
    if (buffer.size >= batchSize) flushBuffer()
  }
  

  private def flushBuffer(): Unit = {
    if (buffer.isEmpty) return
    val sid = simulationId

    try {
      val w = getOrCreateWriter()
      buffer.foreach { event =>
        val record = new GenericData.Record(SCHEMA)
        record.put("entity_id",     Option(event.entityId).getOrElse(""))
        record.put("tick",          event.tick)
        record.put("real_time_ms",  System.currentTimeMillis())
        record.put("lamport_tick",  event.lamportTick)
        record.put("event_type",    event.label)
        record.put("simulation_id", sid)
        record.put("data",          JsonUtil.toJson(event.data))
        w.write(record)
      }
      flushCount += 1
      if (flushCount % 50 == 0)
        logInfo(s"Wrote ${buffer.size} events to $currentFilePath (total flushes: $flushCount)")
      buffer.clear()
    } catch {
      case e: Exception =>
        logError(s"Failed to write Parquet report: ${e.getMessage}", e)
        closeWriter()
    }
  }
  

  override def postStop(): Unit = {
    if (buffer.nonEmpty) flushBuffer()
    closeWriter()
  }


  private def mkdir(dir: String): Unit = {
    val dirPath: Path = Paths.get(dir)
    if (!Files.exists(dirPath)) Files.createDirectories(dirPath)
  }
}
