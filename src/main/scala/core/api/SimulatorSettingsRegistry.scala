package org.interscity.htc
package core.api

import com.typesafe.config.{ Config, ConfigFactory }

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Thread-safe registry for htc.* setting overrides provided via the REST API.
  *
  * Override priority (highest → lowest):
  *   1. Values stored here (set via PUT /api/v1/settings) 2. Environment variables (HTC_*) 3.
  *      application.conf defaults
  *
  * When the API is NOT used, this registry is empty and has zero effect — all configuration
  * continues to come from application.conf / env vars as usual.
  *
  * Actors that want to honour API overrides should use the helper methods [[getInt]],
  * [[getString]], [[getBoolean]] instead of reading directly from `context.system.settings.config`.
  */
object SimulatorSettingsRegistry {

  /** Metadata for a known simulator setting. */
  case class SettingDef(
    configPath: String,
    envVar: String,
    description: String,
    defaultValue: String
  )

  /** Catalog of all known settings exposed via the API. */
  val catalog: List[SettingDef] = List(
    SettingDef(
      "htc.time-manager.total-instances",
      "HTC_TIME_MANAGER_INSTANCES",
      "Total LocalTM routees across the cluster",
      "128"
    ),
    SettingDef(
      "htc.time-manager.max-instances-per-node",
      "HTC_TIME_MANAGER_PER_NODE",
      "Max LocalTM instances per node",
      "4"
    ),
    SettingDef(
      "htc.time-manager.lookahead-window",
      "HTC_TIME_MANAGER_LOOKAHEAD",
      "Look-ahead window in ticks",
      "1"
    ),
    SettingDef(
      "htc.time-manager.window-size",
      "HTC_TIME_MANAGER_WINDOW_SIZE",
      "Window size in ticks",
      "1"
    ),
    SettingDef(
      "htc.time-manager.metrics-log-interval",
      "HTC_TIME_MANAGER_METRICS_INTERVAL",
      "Metrics log interval in ticks",
      "500"
    ),
    SettingDef(
      "htc.time-manager.actor-timeout-ms",
      "HTC_TIME_MANAGER_ACTOR_TIMEOUT_MS",
      "Actor timeout in milliseconds",
      "180000"
    ),
    SettingDef(
      "htc.time-manager.sync-timeout-ms",
      "HTC_TIME_MANAGER_SYNC_TIMEOUT_MS",
      "Sync timeout in milliseconds",
      "30000"
    ),
    SettingDef(
      "htc.time-manager.verbose-logging",
      "HTC_TIME_MANAGER_VERBOSE_LOGGING",
      "Enable verbose time manager logging",
      "true"
    ),
    SettingDef(
      "htc.time-manager.snapshot-interval",
      "HTC_TIME_MANAGER_SNAPSHOT_INTERVAL",
      "Snapshot interval in ticks",
      "10000000"
    ),
    SettingDef("htc.report-manager.enabled", "HTC_REPORT_ENABLED", "Enable report manager", "true"),
    SettingDef(
      "htc.report-manager.json.number-of-instances",
      "HTC_REPORT_JSON_INSTANCES",
      "Total JSON reporter instances",
      "64"
    ),
    SettingDef(
      "htc.report-manager.json.number-of-instances-per-node",
      "HTC_REPORT_JSON_PER_NODE",
      "JSON reporter instances per node",
      "16"
    ),
    SettingDef(
      "htc.report-manager.json.batch-size",
      "HTC_REPORT_JSON_BATCH_SIZE",
      "JSON reporter batch size",
      "500"
    ),
    SettingDef(
      "htc.load-balance-manager.enabled",
      "HTC_LOAD_BALANCE_ENABLED",
      "Enable load balance manager",
      "false"
    ),
    SettingDef(
      "htc.load-balance-manager.strategy",
      "HTC_LOAD_BALANCE_STRATEGY",
      "Load balance strategy: hybrid | default | disabled",
      "default"
    ),
    SettingDef(
      "htc.simulation.config-file",
      "HTC_SIMULATION_CONFIG_FILE",
      "Path to simulation JSON configuration file",
      ""
    ),
    SettingDef(
      "htc.simulation.id",
      "HTC_SIMULATION_ID",
      "Human-readable ID for output files and reports. Defaults to simulation name.",
      ""
    ),
    SettingDef(
      "htc.mobility.city-map-file",
      "HTC_MOBILITY_CITY_MAP_FILE",
      "Path to the city map JSON (Node/Link graph). Required for mobility simulations.",
      "city_map.json"
    ),
    SettingDef(
      "htc.mobility.transit-map-file",
      "HTC_MOBILITY_TRANSIT_MAP_FILE",
      "Path to the transit map JSON (bus/subway stops). Enables dynamic PT mode choice.",
      ""
    ),
    SettingDef(
      "htc.person.truncate-schedule-beyond-duration",
      "HTC_PERSON_TRUNCATE_SCHEDULE",
      "When true, removes Person activities whose endTime > simulation duration at actor startup. " +
        "Use when the input data contains multi-day schedules but the simulation runs only 1 day.",
      "false"
    ),
    SettingDef(
      "htc.actor-trace.enabled",
      "HTC_ACTOR_TRACE_ENABLED",
      "Enable [ACTOR-TRACE] INFO logs for major actor lifecycle events (Person, Bus, Car, Subway, etc.). " +
        "Toggle at runtime via REST API. Remove from source by searching '// #actor-trace'.",
      "false"
    )
  )

  private val catalogByPath: Map[String, SettingDef] = catalog
    .map(
      d => d.configPath -> d
    )
    .toMap
  private val overrides = new ConcurrentHashMap[String, String]()

  private lazy val baseConfig: Config = ConfigFactory.load()

  def set(key: String, value: String): Unit = overrides.put(key, value)
  def setAll(settings: Map[String, String]): Unit = settings.foreach {
    case (k, v) => set(k, v)
  }
  def clear(key: String): Unit = overrides.remove(key)
  def clearAll(): Unit = overrides.clear()

  def get(key: String): Option[String] = Option(overrides.get(key))
  def getAll: Map[String, String] = overrides.asScala.toMap

  /** Effective value: API override > env var > application.conf */
  def effectiveValue(key: String): String =
    Option(overrides.get(key))
      .orElse(
        catalogByPath
          .get(key)
          .flatMap(
            d => sys.env.get(d.envVar)
          )
      )
      .getOrElse {
        try baseConfig.getString(key)
        catch { case _: Exception => "" }
      }

  /** Source of the effective value. */
  def effectiveSource(key: String): String =
    if (overrides.containsKey(key)) "api_override"
    else
      catalogByPath
        .get(key)
        .flatMap(
          d => sys.env.get(d.envVar)
        ) match {
        case Some(_) => "env_var"
        case None    => "application_conf"
      }

  /** Int setting: checks registry override before `fallbackConfig`. */
  def getInt(path: String, fallbackConfig: Config): Int =
    Option(overrides.get(path)).map(_.toInt).getOrElse(fallbackConfig.getInt(path))

  /** String setting: checks registry override before `fallbackConfig`. */
  def getString(path: String, fallbackConfig: Config): String =
    Option(overrides.get(path)).getOrElse(fallbackConfig.getString(path))

  /** Boolean setting: checks registry override before `fallbackConfig`. */
  def getBoolean(path: String, fallbackConfig: Config): Boolean =
    Option(overrides.get(path)).map(_.toBoolean).getOrElse(fallbackConfig.getBoolean(path))
}
