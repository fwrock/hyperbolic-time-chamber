package org.interscity.htc.model.hybrid.util.cache

import org.interscity.htc.core.util.JsonUtil
import org.interscity.htc.model.hybrid.entity.state.model.DynamicLinkCost
import org.interscity.htc.system.database.redis.RedisClient
import redis.clients.jedis.JedisPool

import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters._

/** Redis-based cache strategy (original implementation).
  * 
  * Architecture:
  * - Single centralized Redis instance
  * - All cluster nodes connect to same Redis
  * - Network latency: ~1ms per operation
  * - No synchronization needed (Redis handles it)
  * 
  * Best for:
  * - Multi-node clusters
  * - When consistency is critical
  * - When Redis is already in infrastructure
  */
class RedisCacheStrategy extends WeightCacheStrategy {
  
  private val KEY_PREFIX = "dynamic:link:cost:"
  
  override def publishCost(cost: DynamicLinkCost, ttlSeconds: Int): Try[Unit] = {
    Try {
      val key = KEY_PREFIX + cost.linkId
      val json = JsonUtil.toJson(cost)
      
      val jedis = RedisClient.instance.getPool.getResource
      try {
        jedis.setex(key, ttlSeconds, json)
        () // Convert to Unit
      } finally {
        jedis.close()
      }
    }.recoverWith { case e =>
      System.err.println(s"Redis: Failed to publish cost for link ${cost.linkId}: ${e.getMessage}")
      Failure(e)
    }
  }
  
  override def getCost(linkId: String): Option[DynamicLinkCost] = {
    Try {
      val key = KEY_PREFIX + linkId
      val jedis = RedisClient.instance.getPool.getResource
      try {
        val json = jedis.get(key)
        if (json != null) {
          Try(JsonUtil.fromJson[DynamicLinkCost](json)).toOption
        } else {
          None
        }
      } finally {
        jedis.close()
      }
    }.recover { case e =>
      System.err.println(s"Redis: Failed to get cost for link $linkId: ${e.getMessage}")
      None
    }.get
  }
  
  override def getWeight(linkId: String, staticWeight: Double): Double = {
    getCost(linkId) match {
      case Some(cost) => cost.totalCost
      case None => staticWeight
    }
  }
  
  override def getBatchWeights(linkWeights: Map[String, Double]): Map[String, Double] = {
    linkWeights.map { case (linkId, staticWeight) =>
      linkId -> getWeight(linkId, staticWeight)
    }
  }
  
  override def clearCost(linkId: String): Try[Unit] = {
    Try {
      val key = KEY_PREFIX + linkId
      val jedis = RedisClient.instance.getPool.getResource
      try {
        jedis.del(key)
        () // Convert to Unit
      } finally {
        jedis.close()
      }
    }.recoverWith { case e =>
      System.err.println(s"Redis: Failed to clear cost for link $linkId: ${e.getMessage}")
      Failure(e)
    }
  }
  
  override def clearAllCosts(): Try[Unit] = {
    Try {
      val jedis = RedisClient.instance.getPool.getResource
      try {
        val keys = jedis.keys(KEY_PREFIX + "*").asScala
        keys.foreach(jedis.del)
      } finally {
        jedis.close()
      }
    }.recoverWith { case e =>
      System.err.println(s"Redis: Failed to clear all costs: ${e.getMessage}")
      Failure(e)
    }
  }
  
  override def getStatistics(): (Int, Double, Int) = {
    Try {
      val jedis = RedisClient.instance.getPool.getResource
      try {
        val keys = jedis.keys(KEY_PREFIX + "*").asScala
        val costs = keys.flatMap { key =>
          val json = jedis.get(key)
          if (json != null) Try(JsonUtil.fromJson[DynamicLinkCost](json)).toOption else None
        }
        
        if (costs.isEmpty) {
          (0, 0.0, 0)
        } else {
          val totalLinks = costs.size
          val avgCongestion = costs.map(_.congestionFactor).sum / totalLinks
          val congestedLinks = costs.count(_.isCongested)
          (totalLinks, avgCongestion, congestedLinks)
        }
      } finally {
        jedis.close()
      }
    }.recover { case e =>
      System.err.println(s"Redis: Failed to get statistics: ${e.getMessage}")
      (0, 0.0, 0)
    }.get
  }
}
