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

  // ── Avro schema ──────────────────────────────────────────────────────────

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

  // ── Config ───────────────────────────────────────────────────────────────

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

  // Row-group size controls how much uncompressed data Parquet buffers internally
  // before flushing a column chunk to disk. This is NOT the actor-level batch size.
  // Smaller = data appears on disk sooner, less heap per actor.
  // Larger = better compression ratio (more data for the codec to find patterns).
  // 16 MB default: good balance for simulations of all sizes. Files become visible
  // on disk after ~16 MB of raw events per actor instead of waiting for shutdown.
  private val rowGroupBytes: Long =
    try config.getLong("htc.report-manager.parquet.row-group-size-mb") * 1024L * 1024L
    catch { case _: Exception => 16L * 1024 * 1024 }

  // ZSTD compression level [1-22]. Higher = smaller files, more CPU.
  // Level 6 is a good sweet-spot (≈15% smaller than default level 3, ~1.3× slower).
  private val zstdLevel: Int =
    try config.getInt("htc.report-manager.parquet.zstd-level")
    catch { case _: Exception => 6 }

  // ── Codec ────────────────────────────────────────────────────────────────

  private val codec: CompressionCodecName = compressionCodecName match {
    case "snappy"        => CompressionCodecName.SNAPPY
    case "zstd"          => CompressionCodecName.ZSTD
    case "gzip"          => CompressionCodecName.GZIP
    case "uncompressed"  => CompressionCodecName.UNCOMPRESSED
    case other =>
      logWarn(s"Unknown Parquet compression codec '$other', falling back to SNAPPY")
      CompressionCodecName.SNAPPY
  }

  // ── File path ────────────────────────────────────────────────────────────

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

  // filePath is a def (not lazy val) so a new unique path is generated after a
  // writer failure. If closeWriter() is called due to an error, the next
  // getOrCreateWriter() creates a fresh file instead of overwriting the broken one.
  private def newFilePath(): String = {
    val freshId = java.util.UUID.randomUUID().toString.take(8)
    s"$directory/${prefix}${timeBasedId}_${freshId}_events${extension}"
  }

  // ── State ────────────────────────────────────────────────────────────────

  private val buffer                                = mutable.ArrayBuffer.empty[ReportEvent]
  private var writer: ParquetWriter[GenericRecord]  = _
  private var currentFilePath: String               = _
  private var flushCount: Long                      = 0L

  // ── Writer lifecycle ─────────────────────────────────────────────────────

  private def getOrCreateWriter(): ParquetWriter[GenericRecord] = {
    if (writer == null) {
      mkdir(directory)
      currentFilePath = newFilePath()
      val outputFile = new LocalOutputFile(Paths.get(currentFilePath))

      // Build a Hadoop Configuration to control codec-level settings.
      // This is required even without a real Hadoop cluster — the Parquet
      // library reads these keys internally before writing column chunks.
      val hadoopConf = new Configuration(false)
      // ZSTD compression level (1-22). Parquet reads this Hadoop conf key when codec = ZSTD.
      // The constant ParquetOutputFormat.ZSTD_LEVEL was never public in 1.x — use the string key directly.
      hadoopConf.setInt("parquet.compression.codec.zstd.level", zstdLevel)

      writer = AvroParquetWriter
        .builder[GenericRecord](outputFile)
        .withConf(hadoopConf)
        .withSchema(SCHEMA)
        .withDataModel(GenericData.get())
        .withCompressionCodec(codec)
        .withRowGroupSize(rowGroupBytes)
        // 256 KB pages: better balance for GCS Fuse / S3 (fewer seeks vs 64 KB)
        // and still fine for predicate pushdown in Spark / DuckDB.
        .withPageSize(256 * 1024)
        // PARQUET_2_0 enables delta-encoding for int64 columns (tick, real_time_ms,
        // lamport_tick). tick is monotonically increasing → delta values are tiny
        // → additional 20-40% size reduction on numeric columns vs PARQUET_1_0.
        .withWriterVersion(ParquetProperties.WriterVersion.PARQUET_2_0)
        // Enable dictionary encoding globally; Parquet auto-falls-back to plain
        // encoding when the per-column dict exceeds 1MB, so high-cardinality columns
        // (e.g. `data` JSON payloads) are not penalised.
        .withDictionaryEncoding(true)
        // Disable dictionary for `data`: JSON payloads are almost always unique per row.
        // Forcing a dict on them wastes CPU building a dictionary that immediately overflows.
        .withDictionaryEncoding("data", false)
        // Page-level CRC32 checksums. Detects silent corruption on GCS Fuse / HDFS.
        // Overhead is negligible (<1% CPU) for the safety guarantee on network FS.
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

  // ── Receive ──────────────────────────────────────────────────────────────

  override def onReport(event: ReportEvent): Unit = {
    buffer += event
    if (buffer.size >= batchSize) flushBuffer()
  }

  // ── Flush ─────────────────────────────────────────────────────────────────

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
        // closeWriter invalidates currentFilePath; next getOrCreateWriter() generates
        // a fresh UUID path so buffered events are retried into a new clean file.
        closeWriter()
    }
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  override def postStop(): Unit = {
    if (buffer.nonEmpty) flushBuffer()
    closeWriter()
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def mkdir(dir: String): Unit = {
    val dirPath: Path = Paths.get(dir)
    if (!Files.exists(dirPath)) Files.createDirectories(dirPath)
  }
}
