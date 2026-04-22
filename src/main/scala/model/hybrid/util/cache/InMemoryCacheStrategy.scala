package org.interscity.htc.model.hybrid.util.cache

import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost

import scala.util.{ Failure, Success, Try }
import java.util.concurrent.{ ConcurrentHashMap, Executors, TimeUnit }
import scala.jdk.CollectionConverters._

/** In-memory cache strategy with simple local cache.
  *
  * Architecture:
  *   - Local in-memory cache (ConcurrentHashMap)
  *   - No external dependencies (no Kafka, no Redis, no ActorSystem required)
  *   - No cluster synchronization in this simple version
  *
  * Performance:
  *   - Local reads: ~10 nanoseconds (O(1) HashMap lookup)
  *   - Local writes: ~100 nanoseconds
  *   - Ultra-fast for single-node deployments
  *
  * Best for:
  *   - Single-node deployments (ultra-fast)
  *   - Development and testing
  *   - When cluster synchronization not needed
  *
  * Trade-offs:
  *   - ✅ Extremely fast reads (in-memory)
  *   - ✅ No external dependencies
  *   - ✅ Simple implementation
  *   - ❌ No cluster synchronization (each node has own cache)
  *   - ❌ Higher memory usage per node
  */
class InMemoryCacheStrategy extends WeightCacheStrategy {

  // Local cache for ultra-fast reads
  private val localCache = new ConcurrentHashMap[String, (DynamicLinkCost, Long)]()

  // Daemon scheduler for TTL expiration - no ActorSystem needed
  private val scheduler = Executors.newSingleThreadScheduledExecutor {
    r =>
      val t = new Thread(r, "inmemory-cache-expiry")
      t.setDaemon(true)
      t
  }

  // Background cleanup of expired entries
  private def scheduleExpiration(linkId: String, ttlSeconds: Int): Unit =
    scheduler.schedule(
      new Runnable {
        def run(): Unit = localCache.remove(linkId)
      },
      ttlSeconds.toLong,
      TimeUnit.SECONDS
    )

  override def publishCost(cost: DynamicLinkCost, ttlSeconds: Int): Try[Unit] =
    Try {
      // Update local cache immediately (fast)
      val expiryTime = System.currentTimeMillis() + (ttlSeconds * 1000)
      localCache.put(cost.linkId, (cost, expiryTime))

      // Schedule removal after TTL
      scheduleExpiration(cost.linkId, ttlSeconds)
    } match {
      case Success(_) => Success(())
      case Failure(e) =>
        System.err.println(
          s"InMemory: Failed to publish cost for link ${cost.linkId}: ${e.getMessage}"
        )
        Failure(e)
    }

  override def getCost(linkId: String): Option[DynamicLinkCost] =
    // Ultra-fast local read (no network, no serialization)
    Option(localCache.get(linkId)).flatMap {
      case (cost, expiryTime) =>
        if (System.currentTimeMillis() < expiryTime) {
          Some(cost)
        } else {
          // Expired, remove it
          localCache.remove(linkId)
          None
        }
    }

  override def getWeight(linkId: String, staticWeight: Double): Double =
    getCost(linkId) match {
      case Some(cost) => cost.totalCost
      case None       => staticWeight
    }

  override def getBatchWeights(linkWeights: Map[String, Double]): Map[String, Double] =
    // Extremely fast batch operation (all local)
    linkWeights.map {
      case (linkId, staticWeight) =>
        linkId -> getWeight(linkId, staticWeight)
    }

  override def clearCost(linkId: String): Try[Unit] =
    Try {
      localCache.remove(linkId)
    } match {
      case Success(_) => Success(())
      case Failure(e) =>
        System.err.println(s"InMemory: Failed to clear cost for link $linkId: ${e.getMessage}")
        Failure(e)
    }

  override def clearAllCosts(): Try[Unit] =
    Try {
      localCache.clear()
    } match {
      case Success(_) => Success(())
      case Failure(e) =>
        System.err.println(s"InMemory: Failed to clear all costs: ${e.getMessage}")
        Failure(e)
    }

  override def getStatistics(): (Int, Double, Int) = {
    val now = System.currentTimeMillis()
    val costs = localCache
      .values()
      .asScala
      .collect {
        case (cost, expiryTime) if now < expiryTime => cost
      }
      .toList

    if (costs.isEmpty) {
      (0, 0.0, 0)
    } else {
      val totalLinks = costs.size
      val avgCongestion = costs.map(_.congestionFactor).sum / totalLinks
      val congestedLinks = costs.count(_.isCongested)
      (totalLinks, avgCongestion, congestedLinks)
    }
  }
}
