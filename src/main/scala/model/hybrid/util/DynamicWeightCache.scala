package org.interscity.htc.model.hybrid.util

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost
import org.interscity.htc.model.hybrid.util.cache.{InMemoryCacheStrategy, RedisCacheStrategy, WeightCacheStrategy}

import scala.util.Try

/** Cache for dynamic link costs with configurable strategy.
  * 
  * Supports multiple backends:
  * 
  * 1. **Redis** (centralized):
  *    - Single Redis instance shared by cluster
  *    - ~1ms network latency
  *    - Immediate consistency
  *    - Best for multi-node clusters
  * 
  * 2. **InMemory** (distributed):
  *    - Local ConcurrentHashMap + Pekko Distributed Data
  *    - ~10ns local reads (ultra-fast!)
  *    - Eventually consistent (~10-50ms sync)
  *    - Best for single-node or when eventual consistency OK
  * 
  * Configuration:
  * ```
  * htc.routing.cache-strategy = "redis"  # or "inmemory"
  * ```
  */
object DynamicWeightCache {
  
  private val config = ConfigFactory.load()
  
  private lazy val strategy: WeightCacheStrategy = {
    val strategyType = try {
      config.getString("htc.routing.cache-strategy")
    } catch {
      case _: Exception => "redis" // Default to Redis
    }
    
    strategyType.toLowerCase match {
      case "inmemory" =>
        println("Using InMemory cache strategy (fast local + Pekko Distributed Data)")
        // Note: ActorSystem must be passed from context where it's available
        // For now, we'll use Redis as default until ActorSystem is properly injected
        println("WARNING: InMemory strategy requires ActorSystem, falling back to Redis")
        new RedisCacheStrategy()
        
      case "redis" | _ =>
        println("Using Redis cache strategy (centralized, cluster-wide)")
        new RedisCacheStrategy()
    }
  }
  
  /** Factory method to create strategy with ActorSystem (for InMemory).
    */
  def createStrategy(strategyType: String)(implicit system: ActorSystem): WeightCacheStrategy = {
    strategyType.toLowerCase match {
      case "inmemory" => new InMemoryCacheStrategy()
      case "redis" | _ => new RedisCacheStrategy()
    }
  }
  
  // Delegate all operations to strategy
  
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
