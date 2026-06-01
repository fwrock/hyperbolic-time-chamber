package org.interscity.htc.model.hybrid.util

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost
import org.interscity.htc.model.hybrid.util.cache.{ DisabledCacheStrategy, InMemoryCacheStrategy, KafkaCacheStrategy, WeightCacheStrategy }

import java.util.concurrent.atomic.AtomicLong
import scala.util.Try
import scala.concurrent.ExecutionContext

/** Cache for dynamic link costs with configurable strategy.
  *
  * Supports multiple backends:
  *
  *   1. **Kafka** (recommended):
  *      - LinkActors publish → Kafka topic → all nodes consume
  *      - ~10ns local reads (ultra-fast A* calculation)
  *      - Eventually consistent (~100ms realistic lag)
  *      - Best for multi-node clusters with high-frequency routing
  *
  * 2. **Redis** (centralized):
  *   - Single Redis instance shared by cluster
  *   - ~1ms network latency
  *   - Immediate consistency
  *   - Legacy option for simpler deployments
  *
  * 3. **InMemory** (distributed):
  *   - Local ConcurrentHashMap + Pekko Distributed Data
  *   - ~10ns local reads (ultra-fast!)
  *   - Eventually consistent (~10-50ms sync)
  *   - Best for single-node or when eventual consistency OK
  *
  * Configuration:
  * ``
  * htc.routing.cache-strategy = "kafka"  # or "redis" or "inmemory"
  * ``
  */
object DynamicWeightCache {

  private val config = ConfigFactory.load()

  private lazy val strategy: WeightCacheStrategy = {
    val strategyType =
      try
        config.getString("htc.routing.cache-strategy")
      catch {
        case _: Exception => "inmemory"
      }

    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

    strategyType.toLowerCase match {
      case "kafka" =>
        println(
          "[DynamicWeightCache] Using Kafka cache strategy (distributed pub/sub + local memory)"
        )
        new KafkaCacheStrategy()

      case "inmemory" =>
        println(
          "[DynamicWeightCache] Using InMemory cache strategy (local ConcurrentHashMap, no cluster sync)"
        )
        new InMemoryCacheStrategy()

      case "disabled" | "none" | "off" =>
        println("[DynamicWeightCache] Dynamic weights DISABLED — using static weights only.")
        new DisabledCacheStrategy()

      case other =>
        println(
          s"[DynamicWeightCache] Unknown cache strategy '$other' — falling back to InMemory."
        )
        new InMemoryCacheStrategy()
    }
  }

  /** Factory method to create strategy with ActorSystem. Dynamic weights only work with Kafka;
    * otherwise returns disabled (no-op).
    */
  def createStrategy(strategyType: String)(implicit system: ActorSystem): WeightCacheStrategy = {
    implicit val ec: ExecutionContext = system.dispatcher

    strategyType.toLowerCase match {
      case "kafka"                  => new KafkaCacheStrategy()
      case "inmemory"               => new InMemoryCacheStrategy()
      case "disabled" | "none" | "off" => new DisabledCacheStrategy()
      case _                        => new InMemoryCacheStrategy()
    }
  }

  def publishCost(cost: DynamicLinkCost, ttlSeconds: Int = 60): Try[Unit] =
    strategy.publishCost(cost, ttlSeconds)

  def getCost(linkId: String): Option[DynamicLinkCost] =
    strategy.getCost(linkId)

  // ---------------------------------------------------------------------------
  // Lightweight observability: cheap atomic counters only. No per-link tracking
  // and no allocation on the hot path. Logged every LOG_INTERVAL queries.
  // ---------------------------------------------------------------------------
  private val dynHits      = new AtomicLong(0L)
  private val staticMisses = new AtomicLong(0L)
  private val totalQueries = new AtomicLong(0L)

  private val LOG_INTERVAL: Long =
    try
      config.getLong("htc.routing.dynamic-weight-log-interval")
    catch {
      case _: Exception => 100000L
    }

  def getWeight(linkId: String, staticWeight: Double): Double = {
    val costOpt = strategy.getCost(linkId)
    val w       = costOpt.map(_.totalCost).getOrElse(staticWeight)

    if (costOpt.isDefined) dynHits.incrementAndGet() else staticMisses.incrementAndGet()

    val total = totalQueries.incrementAndGet()
    if (LOG_INTERVAL > 0 && total % LOG_INTERVAL == 0L) {
      val d   = dynHits.get()
      val s   = staticMisses.get()
      val pct = if (total > 0) 100.0 * d / total else 0.0
      println(
        f"[DynamicWeightCache] queries=$total dyn=$d ($pct%.2f%%) static=$s"
      )
    }
    w
  }

  def getBatchWeights(linkWeights: Map[String, Double]): Map[String, Double] =
    strategy.getBatchWeights(linkWeights)

  def clearCost(linkId: String): Try[Unit] =
    strategy.clearCost(linkId)

  def clearAllCosts(): Try[Unit] =
    strategy.clearAllCosts()

  def getStatistics(): (Int, Double, Int) =
    strategy.getStatistics()
}
