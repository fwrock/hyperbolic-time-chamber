package org.interscity.htc
package core.metrics.core

import core.entity.event.EntityEnvelopeEvent

import io.prometheus.client.{ Counter, Gauge, Histogram }

import java.util.concurrent.ConcurrentHashMap

/** Metrics about the managers (SimulationManager, global/local time managers, ReportManager, LoadBalanceManager, ...).
  *
  * Singleton managers run on one node of the cluster; knowing which node, and how busy each manager is, is part of
  * understanding a distributed run. Everything here is recorded by `BaseManager` for every manager, so a new manager
  * is covered without touching it, and `kind` tells a cluster singleton from a per-node manager.
  */
object ManagerMetrics {

  /** Live instances of each manager on THIS node. A cluster singleton is 1 on the node hosting it and absent/0
    * everywhere else; per-node managers (local time managers, ...) can be more than 1.
    */
  val instances: Gauge = Gauge
    .build()
    .name("htc_manager_instances")
    .help("Live instances of a manager on this node; kind=singleton is 1 on the node hosting the cluster singleton")
    .labelNames("manager", "kind")
    .register()

  val messages: Counter = Counter
    .build()
    .name("htc_manager_messages_total")
    .help("Messages handled by a manager, by manager and message class")
    .labelNames("manager", "message")
    .register()

  val handlingSeconds: Histogram = Histogram
    .build()
    .name("htc_manager_message_seconds")
    .help("Time a manager spent handling one message, by manager and message class")
    .labelNames("manager", "message")
    .buckets(0.00001, 0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0)
    .register()

  // --- load balancing (LoadBalanceManager) ---

  val loadObservationEntities: Gauge = Gauge.build()
    .name("htc_loadbalance_node_entities")
    .help("Live entities per observed node in the last complete load observation")
    .labelNames("observed_node").register()

  val loadObservations: Counter = Counter.build()
    .name("htc_loadbalance_observations_total")
    .help("Complete accepted and failed load observations")
    .labelNames("outcome").register()

  val loadObservationTimestamp: Gauge = Gauge.build()
    .name("htc_loadbalance_observation_timestamp_seconds")
    .help("Wall-clock timestamp of the last complete load observation").register()

  val balanceStrategy: Gauge = Gauge.build()
    .name("htc_loadbalance_strategy_info")
    .help("Configured custom load balancing strategy")
    .labelNames("strategy").register()

  val rebalanceEvaluations: Counter = Counter
    .build()
    .name("htc_loadbalance_rebalances_total")
    .help("Rebalance evaluations by trigger reason and whether they produced migrations")
    .labelNames("reason", "result")
    .register()

  val migrationsPlanned: Counter = Counter
    .build()
    .name("htc_loadbalance_migrations_planned_total")
    .help("Shard migrations planned, by reason and source/destination node")
    .labelNames("reason", "from_node", "to_node")
    .register()

  val migrationsCompleted: Counter = Counter
    .build()
    .name("htc_loadbalance_migrations_total")
    .help("Shard migrations finished, by source node, destination node and outcome (completed/skipped)")
    .labelNames("from_node", "to_node", "outcome")
    .register()

  val migrationSeconds: Histogram = Histogram
    .build()
    .name("htc_loadbalance_migration_seconds")
    .help("Duration of a verified shard migration")
    .buckets(0.01, 0.05, 0.1, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 120.0)
    .register()

  // --- label helpers (message classes are cached: this runs once per manager message) ---

  private val MaxMessageClasses = 200
  private val labels = new ConcurrentHashMap[Class[?], String]()

  /** Simple class name of the message (unwrapping a sharding envelope), bounded to MaxMessageClasses distinct values. */
  def messageLabel(message: Any): String = {
    val payload: Any = message match {
      case EntityEnvelopeEvent(_, event) => event
      case other                         => other
    }
    val cls: Class[?] = if (payload == null) classOf[Null] else payload.getClass
    val known = labels.get(cls)
    if (known != null) known
    else {
      val name = try cls.getSimpleName catch { case _: Throwable => cls.getName.substring(cls.getName.lastIndexOf('.') + 1) }
      val label = if (labels.size >= MaxMessageClasses) "other" else if (name.isEmpty) cls.getName else name
      Option(labels.putIfAbsent(cls, label)).getOrElse(label)
    }
  }
}
