package org.interscity.htc
package system.database.clickhouse

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Manages communication with ClickHouse via its HTTP API (port 8123). Uses Java 21's built-in
  * HttpClient — no extra dependency required.
  *
  * All operations are fail-safe: errors are logged and surfaced to the caller so the reporter can
  * decide to drop rather than crash.
  */
class ClickHouseClientManager(
  val host: String,
  val port: Int,
  val database: String,
  val username: String,
  val password: String
) {

  private val baseUrl = s"http://$host:$port/"

  private val client: HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  private val createDatabase =
    s"CREATE DATABASE IF NOT EXISTS $database"

  private val createRawTable =
    s"""CREATE TABLE IF NOT EXISTS $database.vehicle_link_events (
       |    simulation_id         LowCardinality(String),
       |    tick                  UInt64,
       |    real_time_ms          Int64,
       |    link_id               LowCardinality(String),
       |    event_type            Enum8('enter' = 1, 'leave' = 2),
       |    vehicle_id            String,
       |    actor_type            LowCardinality(String),
       |    actor_creation_type   LowCardinality(String),
       |    vehicle_count_on_link UInt32
       |) ENGINE = MergeTree()
       |PARTITION BY (simulation_id, intDiv(tick, 10000))
       |ORDER BY (simulation_id, link_id, tick)
       |SETTINGS index_granularity = 8192""".stripMargin

  private val createAggTable =
    s"""CREATE TABLE IF NOT EXISTS $database.link_vehicle_counts (
       |    simulation_id LowCardinality(String),
       |    link_id       LowCardinality(String),
       |    tick          UInt64,
       |    enters        UInt32,
       |    leaves        UInt32,
       |    peak_count    UInt32
       |) ENGINE = SummingMergeTree()
       |PARTITION BY simulation_id
       |ORDER BY (simulation_id, link_id, tick)""".stripMargin

  private val createMV =
    s"""CREATE MATERIALIZED VIEW IF NOT EXISTS $database.link_vehicle_counts_mv
       |TO $database.link_vehicle_counts AS
       |SELECT
       |    simulation_id,
       |    link_id,
       |    tick,
       |    countIf(event_type = 'enter') AS enters,
       |    countIf(event_type = 'leave') AS leaves,
       |    max(vehicle_count_on_link)    AS peak_count
       |FROM $database.vehicle_link_events
       |GROUP BY simulation_id, link_id, tick""".stripMargin

  /** Creates database, tables and materialized view if they don't exist. Returns Right(()) on
    * success, Left(error message) on failure.
    */
  def createSchemaIfNeeded(): Either[String, Unit] =
    try {
      execute(createDatabase)
      execute(createRawTable)
      execute(createAggTable)
      execute(createMV)
      Right(())
    } catch {
      case e: Exception => Left(e.getMessage)
    }

  /** Inserts a batch of JSONL rows into vehicle_link_events.
    * @param jsonLines
    *   Sequence of JSON strings (one object per line) Returns Right(()) on success, Left(error) on
    *   failure.
    */
  def insertBatch(jsonLines: Seq[String]): Either[String, Unit] =
    if (jsonLines.isEmpty) Right(())
    else
      try {
        val body = jsonLines.mkString("\n")
        val query = s"INSERT INTO $database.vehicle_link_events FORMAT JSONEachRow"
        val url = s"$baseUrl?query=${java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)}"
        val status = postRaw(url, body)
        if (status >= 200 && status < 300) Right(())
        else Left(s"ClickHouse returned HTTP $status")
      } catch {
        case e: Exception => Left(e.getMessage)
      }

  /** Checks whether ClickHouse is reachable (HEAD / with timeout). */
  def isAvailable: Boolean =
    try {
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(baseUrl))
        .timeout(Duration.ofSeconds(3))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.discarding())
      response.statusCode() < 500
    } catch {
      case _: Exception => false
    }

  private def execute(sql: String): Unit = {
    val status = postRaw(baseUrl, sql)
    if (status < 200 || status >= 300)
      throw new RuntimeException(s"ClickHouse DDL failed (HTTP $status): $sql")
  }

  private def postRaw(url: String, body: String): Int = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "text/plain; charset=UTF-8")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))

    if (username.nonEmpty)
      builder.header("X-ClickHouse-User", username)
    if (password.nonEmpty)
      builder.header("X-ClickHouse-Key", password)

    val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
    response.statusCode()
  }
}
