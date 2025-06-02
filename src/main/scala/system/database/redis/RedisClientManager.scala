package org.interscity.htc
package system.database.redis

import redis.clients.jedis.{JedisPool, JedisPoolConfig}

class RedisClientManager {
  private val redisHost = sys.env.getOrElse("REDIS_HOST", "localhost")
  private val redisPort = sys.env.getOrElse("REDIS_PORT", "6379").toInt

  private val pool = new JedisPool(new JedisPoolConfig(), redisHost, redisPort)

  def save(key: String, value: Array[Byte]): Unit = {
    val jedis = pool.getResource
    try {
      jedis.set(key.getBytes, value)
    } finally {
      jedis.close() // devolve ao pool
    }
  }

  def load(key: String): Option[Array[Byte]] = {
    val jedis = pool.getResource
    try {
      val value = jedis.get(key.getBytes)
      Option(value)
    } finally {
      jedis.close()
    }
  }

  def closePool(): Unit = pool.close()
}