package org.interscity.htc.model.hybrid.util

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost
import org.interscity.htc.model.hybrid.util.cache.{ DisabledCacheStrategy, KafkaCacheStrategy, WeightCacheStrategy }

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
  * ```
  * htc.routing.cache-strategy = "kafka"  # or "redis" or "inmemory"
  * ```
  */
object DynamicWeightCache {

  private val config = ConfigFactory.load()

  private lazy val strategy: WeightCacheStrategy = {
    val strategyType =
      try
        config.getString("htc.routing.cache-strategy")
      catch {
        case _: Exception => "kafka"
      }

    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

    strategyType.toLowerCase match {
      case "kafka" =>
        println(
          "[DynamicWeightCache] Using Kafka cache strategy (distributed pub/sub + local memory)"
        )
        new KafkaCacheStrategy()

      case other =>
        println(
          s"[DynamicWeightCache] Dynamic weights DISABLED (requires Kafka, got: '$other'). Using static weights only."
        )
        new DisabledCacheStrategy()
    }
  }

  /** Factory method to create strategy with ActorSystem. Dynamic weights only work with Kafka;
    * otherwise returns disabled (no-op).
    */
  def createStrategy(strategyType: String)(implicit system: ActorSystem): WeightCacheStrategy = {
    implicit val ec: ExecutionContext = system.dispatcher

    strategyType.toLowerCase match {
      case "kafka" => new KafkaCacheStrategy()
      case _       => new DisabledCacheStrategy()
    }
  }

  def publishCost(cost: DynamicLinkCost, ttlSeconds: Int = 60): Try[Unit] =
    strategy.publishCost(cost, ttlSeconds)

  def getCost(linkId: String): Option[DynamicLinkCost] =
    strategy.getCost(linkId)

  def getWeight(linkId: String, staticWeight: Double): Double =
    strategy.getWeight(linkId, staticWeight)

  def getBatchWeights(linkWeights: Map[String, Double]): Map[String, Double] =
    strategy.getBatchWeights(linkWeights)

  def clearCost(linkId: String): Try[Unit] =
    strategy.clearCost(linkId)

  def clearAllCosts(): Try[Unit] =
    strategy.clearAllCosts()

  def getStatistics(): (Int, Double, Int) =
    strategy.getStatistics()
}
