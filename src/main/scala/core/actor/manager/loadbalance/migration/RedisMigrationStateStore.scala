package org.interscity.htc
package core.actor.manager.loadbalance.migration

import org.interscity.htc.system.database.redis.RedisClient

/** Redis-backed migration state store for multi-node clusters.
  *
  * Uses the existing [[system.database.redis.RedisClientManager]] (Jedis pool) to store
  * serialized actor state during shard migration. State entries are stored as Redis keys
  * with a configurable TTL to prevent leaks if an actor fails to restore.
  *
  * Key format: `htc:migration:{entityId}`
  * Value format: `{className}\n{stateBytes}` (className is ASCII, separated by newline from binary state)
  *
  * Redis connection is configured via environment variables:
  *   - `REDIS_HOST` (default: "localhost")
  *   - `REDIS_PORT` (default: 6379)
  *
  * Thread safety: Jedis pool handles concurrent access; each operation borrows and returns a connection.
  */
class RedisMigrationStateStore extends MigrationStateStore {

  private val redis = RedisClient.instance
  private val KEY_PREFIX = "htc:migration:"

  override def saveState(
    entityId: String,
    stateBytes: Array[Byte],
    className: String,
    ttlSeconds: Int = 300
  ): Unit = {
    val key = KEY_PREFIX + entityId
    // Pack: className bytes + separator + state bytes
    val classNameBytes = className.getBytes("UTF-8")
    val separator = Array[Byte]('\n')
    val packed = classNameBytes ++ separator ++ stateBytes

    val jedis = redis.getPool.getResource
    try {
      jedis.setex(key.getBytes("UTF-8"), ttlSeconds.toLong, packed)
    } finally {
      jedis.close()
    }
  }

  override def loadAndRemoveState(entityId: String): Option[(Array[Byte], String)] = {
    val key = KEY_PREFIX + entityId
    val jedis = redis.getPool.getResource
    try {
      val packed = jedis.get(key.getBytes("UTF-8"))
      if (packed == null) return None

      // Delete after reading (consume-once semantics)
      jedis.del(key.getBytes("UTF-8"))

      // Unpack: find first newline separator
      val separatorIndex = packed.indexOf('\n'.toByte)
      if (separatorIndex < 0) return None

      val className = new String(packed, 0, separatorIndex, "UTF-8")
      val stateBytes = packed.slice(separatorIndex + 1, packed.length)
      Some((stateBytes, className))
    } finally {
      jedis.close()
    }
  }

  override def hasState(entityId: String): Boolean = {
    val key = KEY_PREFIX + entityId
    val jedis = redis.getPool.getResource
    try {
      jedis.exists(key)
    } finally {
      jedis.close()
    }
  }

  override def removeState(entityId: String): Unit = {
    val key = KEY_PREFIX + entityId
    val jedis = redis.getPool.getResource
    try {
      jedis.del(key)
    } finally {
      jedis.close()
    }
  }

  override def clear(): Unit = {
    val jedis = redis.getPool.getResource
    try {
      val keys = jedis.keys(KEY_PREFIX + "*")
      if (!keys.isEmpty) {
        jedis.del(keys.toArray(new Array[String](0)): _*)
      }
    } finally {
      jedis.close()
    }
  }

  override def size: Int = {
    val jedis = redis.getPool.getResource
    try {
      val keys = jedis.keys(KEY_PREFIX + "*")
      keys.size()
    } finally {
      jedis.close()
    }
  }

  override def name: String = "redis"
}
