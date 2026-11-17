package org.interscity.htc
package core.metrics

import io.prometheus.client.{ CollectorRegistry, Counter }

/** Optional transport telemetry; categories and directions are closed to bound label cardinality.
  * Outbound totals across JVMs count a physical envelope once; never add inbound to outbound.
  */
final class RemoteTrafficMetrics(registry: CollectorRegistry) {
  private val messages = Counter
    .build()
    .name("htc_remote_messages_total")
    .help("Artery remote envelopes, including runtime and cluster traffic; excludes local messages")
    .labelNames("direction", "category")
    .register(registry)
  private val bytes = Counter
    .build()
    .name("htc_remote_serialized_bytes_total")
    .help("Serialized Artery envelope bytes, including Pekko headers; excludes TCP/IP overhead")
    .labelNames("direction", "category")
    .register(registry)
  private val categories = Vector("model", "scheduling", "coordination", "reporting", "other")
  private val counters = (for {
    direction <- Vector("sent", "received")
    category <- categories
  } yield (direction -> category) -> (messages
    .labels(direction, category), bytes.labels(direction, category))).toMap

  /** Called at the transport boundary; no metadata or model/protocol effects are introduced. */
  def record(direction: String, category: String, size: Int): Unit = {
    require(size >= 0, "remote envelope size must be nonnegative")
    val (count, volume) = counters(direction -> category)
    count.inc()
    volume.inc(size.toDouble)
  }

  /** Snapshot counters without resetting them, including the cluster/startup traffic. */
  def snapshot: Vector[RemoteTrafficSnapshot] = counters.toVector.sortBy(_._1).collect {
    case ((direction, category), (count, volume)) if count.get() > 0 =>
      val total = count.get().toLong
      RemoteTrafficSnapshot(direction, category, total, volume.get().toLong)
  }
}

object RemoteTrafficMetrics {
  private lazy val instance = new RemoteTrafficMetrics(CollectorRegistry.defaultRegistry)

  /** JVM-wide aggregation supports Artery's separate encoder/decoder instrument instances. */
  def record(direction: String, category: String, size: Int): Unit =
    instance.record(direction, category, size)

  /** Final reporting collects the same counters exposed to Prometheus. */
  def snapshot: Vector[RemoteTrafficSnapshot] = instance.snapshot
}
