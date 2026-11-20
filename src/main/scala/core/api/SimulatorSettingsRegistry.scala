package org.interscity.htc
package core.api

import com.typesafe.config.{ Config, ConfigFactory, ConfigResolveOptions, ConfigValueType }

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** Thread-safe registry for htc.* scenario settings and REST API overrides.
  *
  * Override priority (highest → lowest):
  *   1. REST API overrides 2. simulation.json settings 3. Environment variables (HTC_*)
  *   4. application.conf defaults
  *
  * Without API or scenario overrides, configuration comes from application.conf / env vars.
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
    SettingDef("htc.time-manager.scheduling-strategy", "HTC_TIME_MANAGER_SCHEDULING_STRATEGY",
      "Scheduling ownership strategy; auto selects node-local-tick for direct conservative execution and cluster-pool otherwise", "auto"),
    SettingDef("htc.report-manager.placement", "HTC_REPORT_PLACEMENT",
      "Report sink placement; node-local (default) sends each actor's reports to a sink of the same strategy on its own JVM, cluster-pool uses the round-robin pool", "node-local"),
    SettingDef("htc.time-manager.node-local.nodes", "HTC_NODE_LOCAL_SCHEDULER_NODES",
      "Fixed simulation JVM count for node-local-tick; zero freezes the Up cohort at startup", "0"),
    SettingDef("htc.time-manager.node-local.workers-per-node", "HTC_NODE_LOCAL_SCHEDULER_WORKERS",
      "Private tick schedule partitions per simulation JVM", "4"),
    SettingDef("htc.runtime.mode", "HTC_RUNTIME_MODE",
      "Runtime topology, fixed at startup: cluster, or single-node (one JVM without sharding, singleton or cluster-router machinery)", "cluster"),
    SettingDef("htc.time-manager.conservative-transport", "HTC_CONSERVATIVE_TRANSPORT",
      "Conservative interaction transport, fixed before runtime startup", "direct"),
    SettingDef("htc.time-warp.max-ahead-ticks", "HTC_TIME_WARP_MAX_AHEAD_TICKS", "Optimistic tick window ahead of GVT, fixed at startup", "50"),
    SettingDef("htc.time-warp.input-window-ticks", "HTC_TIME_WARP_INPUT_WINDOW_TICKS", "Maximum tick span for progressive optimistic input enrollment", "1000"),
    SettingDef("htc.time-warp.max-uncommitted-events", "HTC_TIME_WARP_MAX_UNCOMMITTED_EVENTS", "Speculative events per LP above GVT; frontier work may exceed this budget", "256"),
    SettingDef("htc.time-warp.max-events-per-turn", "HTC_TIME_WARP_MAX_EVENTS_PER_TURN", "Maximum optimistic inputs before yielding the actor mailbox", "64"),
    SettingDef("htc.time-warp.fossil-collection-enabled", "HTC_TIME_WARP_FOSSIL_COLLECTION_ENABLED", "Reclaim committed optimistic history after a proven GVT cut", "true"),
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
    ),
    SettingDef(
      "htc.timewarp-trace.enabled",
      "HTC_TIMEWARP_TRACE_ENABLED",
      "Enable [TW-TRACE] DEBUG logs for the Time Warp rollback/dispatch/reply-matching path " +
        "(SimulationBaseActor.rollbackAndCascade, OptimisticLocalTimeManager.dispatchNextAvailable, " +
        "Person interaction handling). Toggle at runtime via REST API; requires the DEBUG log level " +
        "to also be enabled for org.interscity.htc.timewarp.trace to actually see lines.",
      "false"
    )
  )

  private val catalogByPath: Map[String, SettingDef] = catalog
    .map(
      d => d.configPath -> d
    )
    .toMap
  private val overrides = new ConcurrentHashMap[String, String]()
  private case class ScenarioLayer(config: Config, values: Map[String, String])
  @volatile private var scenario = ScenarioLayer(ConfigFactory.empty(), Map.empty)
  private val version = new AtomicLong(0L)

  private lazy val baseConfig: Config = ConfigFactory.load()
  /** Declared types/defaults independent of environment string substitutions. */
  private[htc] lazy val declaredDefaults: Config = ConfigFactory.parseResources("application.conf")
    .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false).setAllowUnresolved(true))

  /** Keys that are only read while the process boots (REST server, cluster); changing them through
    * the API is accepted but has no effect until the engine restarts.
    */
  val startupOnlyPrefixes: List[String] = List("htc.api.", "htc.clustering.", "htc.brokers.", "htc.kafka-clients.", "htc.runtime.")

  /** Closed value sets, taken from the code that parses each setting (not from comments). */
  val options: Map[String, List[String]] = {
    val reporters = List("csv", "json", "clickhouse", "parquet", "kafka")
    Map(
      "htc.mobility.meso.entry-density" -> List("inclusive", "existing-vehicles"),
      "htc.mobility.mode-choice.private-cost-model" -> List("shared-dynamic", "car-dynamic-bicycle-static"),
      "htc.load-balance-manager.strategy" -> List("hybrid", "type-aware", "geo-affinity", "default", "disabled"),
      "htc.node.label-strategy" -> List("rank", "animals", "cities"),
      "htc.report-manager.default-strategy" -> reporters,
      "htc.report-manager.enabled-strategies" -> reporters,
      "htc.report-manager.parquet.compression" -> List("snappy", "zstd", "gzip", "uncompressed"),
      "htc.runtime.mode" -> List("cluster", "single-node"),
      "htc.time-manager.conservative-transport" -> List("direct", "channel-closed"),
      "htc.time-manager.scheduling-strategy" -> List("auto", "cluster-pool", "node-local-tick"),
      "htc.report-manager.placement" -> List("cluster-pool", "node-local"),
      "htc.routing.cache-strategy" -> List("kafka", "inmemory", "accounted", "disabled"),
      "htc.mobility.load-policy.transit-trip-policy" -> List("reject", "walk"),
      "htc.snapshot-manager.migration.state-store" -> List("inmemory", "redis"),
      "htc.brokers.kafka.producer.acks" -> List("all", "1", "0"),
      "htc.kafka.producer.acks" -> List("all", "1", "0"),
      "htc.brokers.kafka.producer.compression-type" -> List("none", "gzip", "snappy", "lz4", "zstd"),
      "htc.kafka.producer.compression.type" -> List("none", "gzip", "snappy", "lz4", "zstd"),
      "htc.brokers.kafka.consumer.auto-offset-reset" -> List("earliest", "latest", "none"),
      "htc.kafka.consumer.auto.offset.reset" -> List("earliest", "latest", "none")
    )
  }

  private val secretMarkers = List("password", "secret", "token")

  def isSecret(key: String): Boolean = secretMarkers.exists(key.toLowerCase.contains)

  def isStartupOnly(key: String): Boolean =
    key == "htc.time-manager.conservative-transport" || key == "htc.time-manager.scheduling-strategy" ||
      key == "htc.report-manager.placement" ||
      key.startsWith("htc.time-manager.node-local.") || startupOnlyPrefixes.exists(key.startsWith)

  def set(key: String, value: String): Unit = { overrides.put(key, value); version.incrementAndGet() }
  def setAll(settings: Map[String, String]): Unit = settings.foreach {
    case (k, v) => set(k, v)
  }
  def clear(key: String): Unit = { overrides.remove(key); version.incrementAndGet() }
  def clearAll(): Unit = { overrides.clear(); version.incrementAndGet() }

  /** Atomically replace the scenario layer; omitted values never leak from a previous scenario. */
  def installScenario(config: Config): Unit = {
    val values = config.entrySet().asScala.map(e => e.getKey -> renderValue(config, e.getKey)).toMap
    scenario = ScenarioLayer(config, values)
    version.incrementAndGet()
  }

  def get(key: String): Option[String] = Option(overrides.get(key)).orElse(scenario.values.get(key))
  def getAll: Map[String, String] = overrides.asScala.toMap

  /** Layered view of `base` with scenario settings and API overrides applied on top, so any `htc.*` key can be
    * adjusted without each reader being wired individually. Readers must call this per use (not
    * cache it in a `val` created before the simulation starts) to see later changes.
    */
  def overlay(base: Config): Config = {
    if (overrides.isEmpty && scenario.config.isEmpty) base
    else {
      val v = version.get()
      overlays.synchronized {
        if (overlayVersion != v) { overlays.clear(); overlayVersion = v }
        val hit = overlays.get(base)
        if (hit != null) hit
        else {
          val map = new java.util.HashMap[String, AnyRef]()
          overrides.asScala.foreach {
            case (k, raw) => map.put(k, toConfigValue(base, k, raw))
          }
          val result = ConfigFactory.parseMap(map, "htc-api-overrides")
            .withFallback(scenario.config).withFallback(base).resolve()
          overlays.put(base, result)
          result
        }
      }
    }
  }

  /** `ConfigFactory.load()` plus scenario settings and API overrides. */
  def effective: Config = overlay(baseConfig)

  // identity-keyed (Config.equals is a deep compare); few distinct bases exist per JVM
  private val overlays = new java.util.IdentityHashMap[Config, Config]()
  private var overlayVersion = -1L

  private def toConfigValue(base: Config, key: String, raw: String): AnyRef =
    if (typeOf(declaredDefaults, key) == "list" || typeOf(base, key) == "list") parseList(raw) else raw

  private[api] def parseList(raw: String): java.util.List[String] = {
    val s = raw.trim
    val items =
      if (s.startsWith("[")) {
        val node = jsonMapper.readTree(s)
        node.elements().asScala.map(
          n => if (n.isTextual) n.asText() else n.toString
        ).toList
      } else s.split(",").map(_.trim).filter(_.nonEmpty).toList
    items.asJava
  }

  private val jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper()

  /** "string" | "number" | "boolean" | "list" | "object" | "null" for a key in `base` ("string" when absent). */
  def typeOf(base: Config, key: String): String =
    if (!base.hasPath(key)) "string"
    else base.getValue(key).valueType match {
      case ConfigValueType.NUMBER  => "number"
      case ConfigValueType.BOOLEAN => "boolean"
      case ConfigValueType.LIST    => "list"
      case ConfigValueType.OBJECT  => "object"
      case ConfigValueType.NULL    => "null"
      case _                       => "string"
    }

  /** Every settable key: the curated catalog plus every leaf under `htc.` in the loaded config. */
  def allKeys: List[String] = {
    val fromConf = scala.util.Try(
      baseConfig.getConfig("htc").entrySet().asScala.map(
        e => "htc." + e.getKey
      ).toList
    ).getOrElse(Nil)
    (catalog.map(_.configPath) ++ fromConf).distinct.sorted
  }

  def describe(key: String): String = catalogByPath.get(key).map(_.description).getOrElse("")

  def envVarOf(key: String): String = catalogByPath.get(key).map(_.envVar).getOrElse("")

  def defaultOf(key: String): String =
    catalogByPath.get(key).map(_.defaultValue).getOrElse(
      if (baseConfig.hasPath(key)) renderValue(baseConfig, key) else ""
    )

  /** Value of `key` in `config` rendered as a plain string (lists as JSON arrays). */
  def renderValue(config: Config, key: String): String =
    if (!config.hasPath(key)) ""
    else {
      val v = config.getValue(key)
      v.valueType match {
        case ConfigValueType.STRING => v.unwrapped().toString
        case ConfigValueType.LIST   => v.render(com.typesafe.config.ConfigRenderOptions.concise())
        case _                      => v.unwrapped().toString
      }
    }

  /** Validates `value` for `key` against the type currently defined; returns an error message. */
  def validate(key: String, value: String): Option[String] = {
    if (!key.startsWith("htc.")) Some(s"only htc.* settings can be adjusted (got '$key'); pekko.* is fixed at JVM start")
    else if (key.length <= 4 || key.endsWith(".")) Some(s"invalid key '$key'")
    else {
      val t = typeOf(declaredDefaults, key)
      scala.util.Try {
        t match {
          case "number" =>
            val n = value.trim
            if (n.contains('.') || n.contains('e') || n.contains('E')) n.toDouble else n.toLong
          case "boolean" =>
            if (!Set("true", "false").contains(value.trim.toLowerCase)) throw new IllegalArgumentException("expected true or false")
          case "list" =>
            val items = parseList(value).asScala
            options.get(key).foreach { o =>
              val bad = items.filterNot(o.contains)
              if (bad.nonEmpty) throw new IllegalArgumentException(s"invalid ${bad.mkString(", ")}")
            }
          case "object" => throw new IllegalArgumentException("is a group of settings; set its leaf keys instead")
          case _ => ()
        }
        if (t != "list") options.get(key).foreach { o =>
          if (!o.contains(value.trim)) throw new IllegalArgumentException(s"invalid '${value.trim}'")
        }
      }.failed.toOption.map(e =>
        options.get(key) match {
          case Some(o) => s"'$key' must be one of ${o.mkString(", ")}: ${e.getMessage}"
          case None    => s"'$key' expects $t: ${e.getMessage}"
        })
    }
  }

  /** Effective value: API > simulation.json > env var > application.conf */
  def effectiveValue(key: String): String =
    get(key)
      .orElse(
        catalogByPath
          .get(key)
          .flatMap(
            d => sys.env.get(d.envVar)
          )
      )
      .getOrElse {
        try renderValue(baseConfig, key)
        catch { case _: Exception => "" }
      }

  /** Source of the effective value. */
  def effectiveSource(key: String): String =
    if (overrides.containsKey(key)) "api_override"
    else if (scenario.values.contains(key)) "simulation_json"
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
    get(path).map(_.toInt).getOrElse(fallbackConfig.getInt(path))

  /** String setting: checks registry override before `fallbackConfig`. */
  def getString(path: String, fallbackConfig: Config): String =
    get(path).getOrElse(fallbackConfig.getString(path))

  /** Boolean setting: checks registry override before `fallbackConfig`. */
  def getBoolean(path: String, fallbackConfig: Config): Boolean =
    get(path).map(_.toBoolean).getOrElse(fallbackConfig.getBoolean(path))
}
