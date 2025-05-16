package org.interscity.htc
package system.database.redis

import redis.clients.jedis.Jedis

class RedisClientManager {
  private val redisHost = sys.env.getOrElse("REDIS_HOST", "localhost")
  private val redisPort = sys.env.getOrElse("REDIS_PORT", "6379").toInt

  private val client = new Jedis(redisHost, redisPort)

  def save(key: String, value: Array[Byte]): Unit =
    client.set(key.getBytes, value)

  def load(key: String): Option[Array[Byte]] =
    Option(client.get(key.getBytes))

  def closeConnection(): Unit =
    client.close()
}
